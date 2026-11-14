package com.soat.tech.challenge.oficina.os.integration

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.amqp.core.AmqpAdmin
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.TopicExchange
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.random.Random

/**
 * Drives the saga from the edge: HTTP on this service, and Billing/Execution simulated by publishing their
 * events on the oficina exchange (contract envelope) and reading the commands from test queues bound to
 * the same exchange.
 */
abstract class SagaIntegrationTestSupport : IntegrationTestSupport() {

	@Autowired
	protected lateinit var rabbitTemplate: RabbitTemplate

	@Autowired
	protected lateinit var amqpAdmin: AmqpAdmin

	@Autowired
	protected lateinit var jdbc: JdbcTemplate

	protected lateinit var token: String

	@BeforeEach
	fun setUpSaga() {
		val exchange = TopicExchange("oficina", true, false)
		listOf(BILLING_TEST_QUEUE to "command.billing.#", EXECUTION_TEST_QUEUE to "command.execution.#").forEach { (name, pattern) ->
			val queue = Queue(name, false, false, false)
			amqpAdmin.declareQueue(queue)
			amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(pattern))
		}
		val login = post("/api/public/auth/login", """{"username":"atendente","password":"atendente"}""")
		assertEquals(200, login.statusCode())
		token = body(login)["accessToken"].asText()
	}

	protected data class ReceivedCommand(val envelope: JsonNode, val properties: MessageProperties, val routingKey: String?) {
		val type: String get() = envelope["type"].asText()
		val payload: JsonNode get() = envelope["payload"]
	}

	/** Opens a work order; [correlationId] becomes the correlation id of the whole saga. */
	protected fun openWorkOrder(correlationId: String = "itest-${UUID.randomUUID()}"): String {
		val response = postWithCorrelation("/api/work-orders", workOrderBody(randomCpf(), randomPlate()), correlationId)
		assertEquals(201, response.statusCode(), response.body())
		return body(response)["id"].asText()
	}

	protected fun workOrderBody(cpf: String, plate: String, quantity: Int = 1) = """
		{
		  "customer": { "taxId": "$cpf", "name": "Maria Silva", "email": "maria@example.com", "phone": "11999990000" },
		  "vehicle": { "plate": "$plate", "brand": "Fiat", "model": "Argo", "year": 2022 },
		  "services": [ { "code": "TROCA_OLEO", "quantity": $quantity } ],
		  "parts": [ { "code": "FILTRO_OLEO", "quantity": 2 } ]
		}
	""".trimIndent()

	protected fun postWithCorrelation(path: String, body: String, correlationId: String): HttpResponse<String> {
		val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer $token")
			.header("X-Correlation-Id", correlationId)
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build()
		return http.send(request, HttpResponse.BodyHandlers.ofString())
	}

	/** Publishes an event as Billing/Execution would: contract envelope, plus an unknown field. Returns the message id. */
	protected fun publishEvent(
		type: String,
		routingKey: String,
		sagaId: String,
		payload: String,
		messageId: String = UUID.randomUUID().toString(),
	): String {
		val envelope = """{"messageId":"$messageId","sagaId":"$sagaId","type":"$type","occurredAt":"2026-11-12T15:04:05.123Z",""" +
			""""correlationId":"itest-corr-happy","payload":$payload,"producer":"itest"}"""
		val properties = MessageProperties().apply {
			contentType = MessageProperties.CONTENT_TYPE_JSON
			deliveryMode = MessageDeliveryMode.PERSISTENT
			this.messageId = messageId
			this.type = type
			setHeader("x-correlation-id", "itest-corr-happy")
		}
		rabbitTemplate.send("oficina", routingKey, Message(envelope.toByteArray(), properties))
		return messageId
	}

	/** Reads the test queue until the command of this saga shows up (other tests share the queue). */
	protected fun awaitCommand(queue: String, type: String, sagaId: String): ReceivedCommand =
		awaitCommands(queue, sagaId, listOf(type)).single { it.type == type }

	/**
	 * Reads the test queue until every type in [types] was published for [sagaId], then keeps reading for
	 * [settleMs] to catch commands that should not be there. Returns every command of the saga, in order.
	 */
	protected fun awaitCommands(queue: String, sagaId: String, types: List<String>, settleMs: Long = 0): List<ReceivedCommand> {
		val received = mutableListOf<ReceivedCommand>()
		val deadline = System.currentTimeMillis() + TIMEOUT_MS
		while (System.currentTimeMillis() < deadline && !received.map { it.type }.containsAll(types)) {
			receiveFor(queue, sagaId, 500)?.let(received::add)
		}
		if (!received.map { it.type }.containsAll(types)) {
			throw AssertionError("$types for $sagaId not published to $queue (got ${received.map { it.type }})")
		}
		val settle = System.currentTimeMillis() + settleMs
		while (System.currentTimeMillis() < settle) {
			receiveFor(queue, sagaId, 200)?.let(received::add)
		}
		return received
	}

	private fun receiveFor(queue: String, sagaId: String, timeoutMs: Long): ReceivedCommand? {
		val message = rabbitTemplate.receive(queue, timeoutMs) ?: return null
		val envelope = json.readTree(message.body)
		if (envelope["sagaId"].asText() != sagaId) return null
		return ReceivedCommand(envelope, message.messageProperties, message.messageProperties.receivedRoutingKey)
	}

	protected fun awaitStatus(id: String, status: String): JsonNode = awaitWorkOrder(id, "status $status") { it["status"].asText() == status }

	protected fun awaitSagaStatus(id: String, status: String): JsonNode =
		awaitWorkOrder(id, "saga $status") { it["saga"]["status"].asText() == status }

	protected fun awaitWorkOrder(id: String, description: String, condition: (JsonNode) -> Boolean): JsonNode {
		val deadline = System.currentTimeMillis() + TIMEOUT_MS
		var last: JsonNode? = null
		while (System.currentTimeMillis() < deadline) {
			last = body(get("/api/work-orders/$id", token))
			if (condition(last)) return last
			Thread.sleep(100)
		}
		throw AssertionError("work order $id did not reach $description (last: $last)")
	}

	/** Waits until the consumer recorded [messageId] (the event was handled, applied or not). */
	protected fun awaitProcessed(messageId: String) {
		val deadline = System.currentTimeMillis() + TIMEOUT_MS
		while (System.currentTimeMillis() < deadline) {
			if (jdbc.queryForObject("select count(*) from processed_message where message_id = ?", Int::class.java, messageId) == 1) return
			Thread.sleep(100)
		}
		throw AssertionError("message $messageId was not processed")
	}

	protected fun history(id: String): List<Triple<String?, String, String>> = body(get("/api/work-orders/$id/history", token))
		.map { Triple(it["fromStatus"].takeUnless(JsonNode::isNull)?.asText(), it["toStatus"].asText(), it["reason"].asText()) }

	protected fun pendingCompensations(workOrder: JsonNode): List<String> = workOrder["saga"]["pendingCompensations"].map { it.asText() }

	protected fun randomCpf(): String {
		val base = (1..9).map { Random.nextInt(10) }
		if (base.all { it == base[0] }) return randomCpf()
		fun digit(numbers: List<Int>): Int {
			val sum = numbers.mapIndexed { i, d -> d * (numbers.size + 1 - i) }.sum()
			val r = sum % 11
			return if (r < 2) 0 else 11 - r
		}
		val d1 = digit(base)
		val d2 = digit(base + d1)
		return (base + d1 + d2).joinToString("")
	}

	protected fun randomPlate(): String {
		val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
		fun l() = letters[Random.nextInt(letters.length)]
		fun d() = Random.nextInt(10)
		return "${l()}${l()}${l()}${d()}${l()}${d()}${d()}"
	}

	// ---- events of the participants ----

	protected fun quoteGenerated(id: String, quoteId: String, total: Long = 21000) = publishEvent(
		"QuoteGenerated", "event.billing.quote-generated", id,
		"""{"workOrderId":"$id","quoteId":"$quoteId","servicesTotalCents":12000,"partsTotalCents":${total - 12000},"totalCents":$total}""",
	)

	protected fun quoteApproved(id: String, quoteId: String, total: Long = 21000) =
		publishEvent("QuoteApproved", "event.billing.quote-approved", id, """{"workOrderId":"$id","quoteId":"$quoteId","totalCents":$total}""")

	protected fun paymentApproved(id: String, quoteId: String, paymentId: String) = publishEvent(
		"PaymentApproved", "event.billing.payment-approved", id,
		"""{"workOrderId":"$id","quoteId":"$quoteId","paymentId":"$paymentId","amountCents":21000,"provider":"SIMULATED","providerPaymentId":"sim-1"}""",
	)

	protected fun executionStatusChanged(id: String, status: String) =
		publishEvent("ExecutionStatusChanged", "event.execution.status-changed", id, """{"workOrderId":"$id","status":"$status"}""")

	protected fun quoteCancelled(id: String, quoteId: String) =
		publishEvent("QuoteCancelled", "event.billing.quote-cancelled", id, """{"workOrderId":"$id","quoteId":"$quoteId"}""")

	protected fun paymentRefunded(id: String, paymentId: String) =
		publishEvent("PaymentRefunded", "event.billing.payment-refunded", id, """{"workOrderId":"$id","paymentId":"$paymentId"}""")

	protected companion object {
		const val BILLING_TEST_QUEUE = "test.billing.commands"
		const val EXECUTION_TEST_QUEUE = "test.execution.commands"
		const val DLQ = "os.events.dlq"
		const val TIMEOUT_MS = 15_000L
	}
}
