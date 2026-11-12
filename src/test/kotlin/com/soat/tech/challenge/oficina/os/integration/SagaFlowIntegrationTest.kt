package com.soat.tech.challenge.oficina.os.integration

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
 * events on the oficina exchange (envelope of the spec) and reading the commands from test queues bound
 * to the same exchange.
 */
class SagaFlowIntegrationTest : IntegrationTestSupport() {

	@Autowired
	private lateinit var rabbitTemplate: RabbitTemplate

	@Autowired
	private lateinit var amqpAdmin: AmqpAdmin

	@Autowired
	private lateinit var jdbc: JdbcTemplate

	private lateinit var token: String

	@BeforeEach
	fun setUp() {
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

	@Test
	fun `work order goes from RECEIVED to DELIVERED through the saga with the full history`() {
		val cpf = randomCpf()
		val plate = randomPlate()
		val created = postWithCorrelation("/api/work-orders", workOrderBody(cpf, plate), "itest-corr-happy")
		assertEquals(201, created.statusCode(), created.body())
		val workOrder = body(created)
		val id = workOrder["id"].asText()
		assertEquals("RECEIVED", workOrder["status"].asText())
		assertEquals("Recebida", workOrder["statusLabel"].asText())
		assertEquals(cpf, workOrder["customer"]["taxId"].asText())
		assertEquals(plate, workOrder["vehicle"]["plate"].asText())
		assertEquals("TROCA_OLEO", workOrder["services"][0]["code"].asText())
		assertEquals(2, workOrder["parts"][0]["quantity"].asInt())
		assertTrue(workOrder["quoteId"].isNull)
		assertTrue(workOrder["totalCents"].isNull)
		assertEquals("RUNNING", workOrder["saga"]["status"].asText())
		assertEquals("QUOTE_GENERATION", workOrder["saga"]["step"].asText())

		// 1. GenerateQuote reaches billing.commands' binding.
		val generateQuote = awaitCommand(BILLING_TEST_QUEUE, "GenerateQuote", id)
		assertEquals("command.billing.generate-quote", generateQuote.routingKey)
		assertEquals(MessageProperties.CONTENT_TYPE_JSON, generateQuote.properties.contentType)
		assertEquals(MessageDeliveryMode.PERSISTENT, generateQuote.properties.receivedDeliveryMode)
		assertEquals(generateQuote.envelope["messageId"].asText(), generateQuote.properties.messageId)
		assertEquals("itest-corr-happy", generateQuote.properties.getHeader<String>("x-correlation-id"))
		assertFalse(generateQuote.properties.headers.containsKey("__TypeId__"))
		assertEquals(id, generateQuote.envelope["sagaId"].asText())
		assertEquals("itest-corr-happy", generateQuote.envelope["correlationId"].asText())
		val quotePayload = generateQuote.envelope["payload"]
		assertEquals(id, quotePayload["workOrderId"].asText())
		assertEquals(workOrder["trackingCode"].asText(), quotePayload["trackingCode"].asText())
		assertEquals("Maria Silva", quotePayload["customer"]["name"].asText())
		assertEquals("maria@example.com", quotePayload["customer"]["email"].asText())
		assertEquals("TROCA_OLEO", quotePayload["services"][0]["code"].asText())
		assertEquals("FILTRO_OLEO", quotePayload["parts"][0]["code"].asText())

		// 2-3. Billing priced the quote.
		val quoteId = UUID.randomUUID().toString()
		publishEvent(
			"QuoteGenerated", "event.billing.quote-generated", id,
			"""{"workOrderId":"$id","quoteId":"$quoteId","servicesTotalCents":12000,"partsTotalCents":9000,"totalCents":21000}""",
		)
		val pending = awaitStatus(id, "PENDING_APPROVAL")
		assertEquals("Aguardando Aprovação", pending["statusLabel"].asText())
		assertEquals(quoteId, pending["quoteId"].asText())
		assertEquals(12000, pending["servicesTotalCents"].asLong())
		assertEquals(9000, pending["partsTotalCents"].asLong())
		assertEquals(21000, pending["totalCents"].asLong())
		assertEquals("CUSTOMER_APPROVAL", pending["saga"]["step"].asText())

		// 4-5. Customer approved: payment is requested.
		publishEvent("QuoteApproved", "event.billing.quote-approved", id, """{"workOrderId":"$id","quoteId":"$quoteId","totalCents":21000}""")
		assertEquals("PAYMENT", awaitStatus(id, "AWAITING_PAYMENT")["saga"]["step"].asText())
		val requestPayment = awaitCommand(BILLING_TEST_QUEUE, "RequestPayment", id)
		assertEquals("command.billing.request-payment", requestPayment.routingKey)
		assertEquals("itest-corr-happy", requestPayment.envelope["correlationId"].asText())
		assertEquals(quoteId, requestPayment.envelope["payload"]["quoteId"].asText())
		assertEquals(21000, requestPayment.envelope["payload"]["amountCents"].asLong())

		// 6-7. Payment approved: execution is started.
		val paymentId = UUID.randomUUID().toString()
		publishEvent(
			"PaymentApproved", "event.billing.payment-approved", id,
			"""{"workOrderId":"$id","quoteId":"$quoteId","paymentId":"$paymentId","amountCents":21000,"provider":"SIMULATED","providerPaymentId":"sim-1"}""",
		)
		val queued = awaitStatus(id, "QUEUED")
		assertEquals(paymentId, queued["paymentId"].asText())
		assertEquals("EXECUTION", queued["saga"]["step"].asText())
		val startExecution = awaitCommand(EXECUTION_TEST_QUEUE, "StartExecution", id)
		assertEquals("command.execution.start-execution", startExecution.routingKey)
		val execPayload = startExecution.envelope["payload"]
		assertEquals("Maria Silva", execPayload["customerName"].asText())
		assertEquals(plate, execPayload["vehicle"]["plate"].asText())
		assertEquals("Fiat", execPayload["vehicle"]["brand"].asText())
		assertEquals("Argo", execPayload["vehicle"]["model"].asText())
		assertEquals(2022, execPayload["vehicle"]["year"].asInt())
		assertEquals(1, execPayload["services"][0]["quantity"].asInt())
		assertEquals("itest-corr-happy", startExecution.envelope["correlationId"].asText())

		// 9-12. Execution events.
		publishEvent("ExecutionStatusChanged", "event.execution.status-changed", id, """{"workOrderId":"$id","status":"IN_DIAGNOSIS"}""")
		awaitStatus(id, "IN_DIAGNOSIS")
		publishEvent("ExecutionStatusChanged", "event.execution.status-changed", id, """{"workOrderId":"$id","status":"IN_EXECUTION"}""")
		awaitStatus(id, "IN_EXECUTION")
		publishEvent("ExecutionFinished", "event.execution.finished", id, """{"workOrderId":"$id","finishedAt":"2026-11-12T18:00:00.000Z"}""")
		assertEquals("DELIVERY", awaitStatus(id, "FINALIZED")["saga"]["step"].asText())

		// 13. Delivery over HTTP completes the saga.
		val delivered = post("/api/work-orders/$id/delivery", null, token)
		assertEquals(200, delivered.statusCode(), delivered.body())
		assertEquals("DELIVERED", body(delivered)["status"].asText())
		assertEquals("Entregue", body(delivered)["statusLabel"].asText())
		assertEquals("COMPLETED", body(delivered)["saga"]["status"].asText())
		assertEquals(409, post("/api/work-orders/$id/delivery", null, token).statusCode())

		val history = body(get("/api/work-orders/$id/history", token))
		val transitions = history.map { Triple(it["fromStatus"].takeUnless(JsonNode::isNull)?.asText(), it["toStatus"].asText(), it["reason"].asText()) }
		assertEquals(
			listOf(
				Triple(null, "RECEIVED", "WorkOrderOpened"),
				Triple("RECEIVED", "PENDING_APPROVAL", "QuoteGenerated"),
				Triple("PENDING_APPROVAL", "AWAITING_PAYMENT", "QuoteApproved"),
				Triple("AWAITING_PAYMENT", "QUEUED", "PaymentApproved"),
				Triple("QUEUED", "IN_DIAGNOSIS", "ExecutionStatusChanged"),
				Triple("IN_DIAGNOSIS", "IN_EXECUTION", "ExecutionStatusChanged"),
				Triple("IN_EXECUTION", "FINALIZED", "ExecutionFinished"),
				Triple("FINALIZED", "DELIVERED", "DeliveryRegistered"),
			),
			transitions,
		)
		val instants = history.map { java.time.Instant.parse(it["occurredAt"].asText()) }
		assertEquals(instants.sorted(), instants)
	}

	@Test
	fun `repeated message id does not duplicate the effect`() {
		val id = openWorkOrder()
		val messageId = UUID.randomUUID().toString()
		val quoteId = UUID.randomUUID().toString()
		val payload = """{"workOrderId":"$id","quoteId":"$quoteId","servicesTotalCents":1,"partsTotalCents":2,"totalCents":3}"""
		publishEvent("QuoteGenerated", "event.billing.quote-generated", id, payload, messageId)
		publishEvent("QuoteGenerated", "event.billing.quote-generated", id, payload, messageId)
		// Single consumer: once the next event is applied the duplicate was already consumed.
		publishEvent("QuoteApproved", "event.billing.quote-approved", id, """{"workOrderId":"$id","quoteId":"$quoteId","totalCents":3}""")
		awaitStatus(id, "AWAITING_PAYMENT")

		val reasons = body(get("/api/work-orders/$id/history", token)).map { it["reason"].asText() }
		assertEquals(listOf("WorkOrderOpened", "QuoteGenerated", "QuoteApproved"), reasons)
		assertEquals(1, jdbc.queryForObject("select count(*) from processed_message where message_id = ?", Int::class.java, messageId))
		awaitCommand(BILLING_TEST_QUEUE, "RequestPayment", id)
	}

	@Test
	fun `out of order and failure events leave the work order untouched`() {
		val id = openWorkOrder()
		val quoteId = UUID.randomUUID().toString()
		publishEvent("ExecutionFinished", "event.execution.finished", id, """{"workOrderId":"$id","finishedAt":"2026-11-12T18:00:00Z"}""")
		publishEvent("QuoteRejected", "event.billing.quote-rejected", id, """{"workOrderId":"$id","quoteId":"$quoteId","reason":"caro"}""")
		publishEvent("QuoteGenerationFailed", "event.billing.quote-generation-failed", id, """{"workOrderId":"$id","reason":"X"}""")
		publishEvent(
			"QuoteGenerated", "event.billing.quote-generated", id,
			"""{"workOrderId":"$id","quoteId":"$quoteId","servicesTotalCents":1,"partsTotalCents":2,"totalCents":3}""",
		)
		awaitStatus(id, "PENDING_APPROVAL")

		val reasons = body(get("/api/work-orders/$id/history", token)).map { it["reason"].asText() }
		assertEquals(listOf("WorkOrderOpened", "QuoteGenerated"), reasons)
	}

	@Test
	fun `invalid json and type outside the contract go to the DLQ without retry`() {
		while (rabbitTemplate.receive(DLQ) != null) {
			// drain leftovers
		}
		val invalid = Message("not json".toByteArray(), MessageProperties().apply { contentType = MessageProperties.CONTENT_TYPE_JSON })
		rabbitTemplate.send("oficina", "event.billing.quote-generated", invalid)
		val first = rabbitTemplate.receive(DLQ, 10_000)
		assertNotNull(first)
		assertEquals("not json", String(first!!.body))
		assertEquals("os.events", first.messageProperties.xDeathHeader?.firstOrNull()?.get("queue"))

		val bogus = UUID.randomUUID().toString()
		val bogusEnvelope = """{"messageId":"${UUID.randomUUID()}","sagaId":"$bogus","type":"Bogus","occurredAt":"2026-11-12T15:00:00Z","correlationId":"c","payload":{}}"""
		rabbitTemplate.send("oficina", "event.billing.bogus", Message(bogusEnvelope.toByteArray(), MessageProperties()))
		val second = rabbitTemplate.receive(DLQ, 10_000)
		assertNotNull(second)
		assertEquals(bogusEnvelope, String(second!!.body))
	}

	@Test
	fun `processing failure is retried three times and then dead-lettered without effect`() {
		val id = openWorkOrder()
		// Corrupted saga row: loading it fails on every attempt (not a poison message, so it is retried).
		jdbc.update("update saga_os set passo_atual = 'BROKEN' where id = ?", id)
		val messageId = UUID.randomUUID().toString()
		val started = System.nanoTime()
		publishEvent(
			"QuoteGenerated", "event.billing.quote-generated", id,
			"""{"workOrderId":"$id","quoteId":"${UUID.randomUUID()}","servicesTotalCents":1,"partsTotalCents":2,"totalCents":3}""",
			messageId,
		)

		val deadline = System.currentTimeMillis() + TIMEOUT_MS
		var dead: Message? = null
		while (dead == null && System.currentTimeMillis() < deadline) {
			dead = rabbitTemplate.receive(DLQ, 500)?.takeIf { it.messageProperties.messageId == messageId }
		}
		assertNotNull(dead, "message was not dead-lettered")
		val elapsedMs = (System.nanoTime() - started) / 1_000_000
		// Back-off 500 ms + 1000 ms between the three attempts.
		assertTrue(elapsedMs >= 1_400, "dead-lettered after $elapsedMs ms, without the retries")
		assertEquals(0, jdbc.queryForObject("select count(*) from processed_message where message_id = ?", Int::class.java, messageId))
		assertEquals("RECEIVED", jdbc.queryForObject("select status from ordens_servico where id = ?", String::class.java, id))
	}

	@Test
	fun `invalid requests are refused with the mapped status`() {
		val noItems = """{"customer":{"taxId":"${randomCpf()}","name":"A"},"vehicle":{"plate":"${randomPlate()}","brand":"B","model":"C","year":2020}}"""
		assertEquals(400, post("/api/work-orders", noItems, token).statusCode())
		val zeroQuantity = post("/api/work-orders", workOrderBody(randomCpf(), randomPlate(), quantity = 0), token)
		assertEquals(400, zeroQuantity.statusCode())
		// Refused by Bean Validation on the list elements, before reaching the domain.
		assertEquals("Bad Request", body(zeroQuantity)["message"].asText())
		assertEquals(400, post("/api/work-orders", workOrderBody("11111111111", randomPlate()), token).statusCode())
		assertEquals(400, post("/api/work-orders", workOrderBody(randomCpf(), "12"), token).statusCode())
		assertEquals(400, post("/api/work-orders", """{"vehicle":{}}""", token).statusCode())
		assertEquals(401, post("/api/work-orders", workOrderBody(randomCpf(), randomPlate()), null).statusCode())

		val plate = randomPlate()
		assertEquals(201, post("/api/work-orders", workOrderBody(randomCpf(), plate), token).statusCode())
		assertEquals(409, post("/api/work-orders", workOrderBody(randomCpf(), plate), token).statusCode())

		assertEquals(404, get("/api/work-orders/${UUID.randomUUID()}", token).statusCode())
		assertEquals(404, get("/api/work-orders/${UUID.randomUUID()}/history", token).statusCode())
		assertEquals(400, get("/api/work-orders/not-a-uuid", token).statusCode())
		val id = openWorkOrder()
		assertEquals(409, post("/api/work-orders/$id/delivery", null, token).statusCode())
		assertEquals("RECEIVED", body(get("/api/work-orders/$id", token))["status"].asText())
	}

	// ---- helpers ----

	private data class ReceivedCommand(val envelope: JsonNode, val properties: MessageProperties, val routingKey: String?)

	private fun openWorkOrder(): String {
		val response = post("/api/work-orders", workOrderBody(randomCpf(), randomPlate()), token)
		assertEquals(201, response.statusCode(), response.body())
		return body(response)["id"].asText()
	}

	private fun workOrderBody(cpf: String, plate: String, quantity: Int = 1) = """
		{
		  "customer": { "taxId": "$cpf", "name": "Maria Silva", "email": "maria@example.com", "phone": "11999990000" },
		  "vehicle": { "plate": "$plate", "brand": "Fiat", "model": "Argo", "year": 2022 },
		  "services": [ { "code": "TROCA_OLEO", "quantity": $quantity } ],
		  "parts": [ { "code": "FILTRO_OLEO", "quantity": 2 } ]
		}
	""".trimIndent()

	private fun postWithCorrelation(path: String, body: String, correlationId: String): HttpResponse<String> {
		val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer $token")
			.header("X-Correlation-Id", correlationId)
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build()
		return http.send(request, HttpResponse.BodyHandlers.ofString())
	}

	/** Publishes an event as Billing/Execution would: envelope of the spec, plus an unknown field. */
	private fun publishEvent(type: String, routingKey: String, sagaId: String, payload: String, messageId: String = UUID.randomUUID().toString()) {
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
	}

	/** Reads the test queue until the command of this saga shows up (other tests share the queue). */
	private fun awaitCommand(queue: String, type: String, sagaId: String): ReceivedCommand {
		val deadline = System.currentTimeMillis() + TIMEOUT_MS
		while (System.currentTimeMillis() < deadline) {
			val message = rabbitTemplate.receive(queue, 500) ?: continue
			val envelope = json.readTree(message.body)
			if (envelope["sagaId"].asText() == sagaId && envelope["type"].asText() == type) {
				return ReceivedCommand(envelope, message.messageProperties, message.messageProperties.receivedRoutingKey)
			}
		}
		throw AssertionError("$type for $sagaId not published to $queue")
	}

	private fun awaitStatus(id: String, status: String): JsonNode {
		val deadline = System.currentTimeMillis() + TIMEOUT_MS
		var last: JsonNode? = null
		while (System.currentTimeMillis() < deadline) {
			last = body(get("/api/work-orders/$id", token))
			if (last["status"].asText() == status) return last
			Thread.sleep(100)
		}
		throw AssertionError("work order $id did not reach $status (last: ${last?.get("status")})")
	}

	private fun randomCpf(): String {
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

	private fun randomPlate(): String {
		val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
		fun l() = letters[Random.nextInt(letters.length)]
		fun d() = Random.nextInt(10)
		return "${l()}${l()}${l()}${d()}${l()}${d()}${d()}"
	}

	private companion object {
		const val BILLING_TEST_QUEUE = "test.billing.commands"
		const val EXECUTION_TEST_QUEUE = "test.execution.commands"
		const val DLQ = "os.events.dlq"
		const val TIMEOUT_MS = 15_000L
	}
}
