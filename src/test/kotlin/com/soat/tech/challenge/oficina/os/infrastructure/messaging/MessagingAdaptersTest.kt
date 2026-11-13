package com.soat.tech.challenge.oficina.os.infrastructure.messaging

import com.soat.tech.challenge.oficina.os.application.messaging.MessageType
import com.soat.tech.challenge.oficina.os.application.messaging.OutboundCommand
import com.soat.tech.challenge.oficina.os.application.messaging.RequestPaymentPayload
import com.soat.tech.challenge.oficina.os.application.saga.SagaOrchestrator
import com.soat.tech.challenge.oficina.os.application.saga.SagaOutcome
import com.soat.tech.challenge.oficina.os.support.RecordingTracing
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.TopicExchange
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.boot.retry.RetryPolicySettings
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagingAdaptersTest {

	private val codec = EnvelopeCodec(jacksonMapperBuilder().build())
	private val tracing = RecordingTracing()

	@Test
	fun `topology declares exchanges queues dead letter queues and bindings of the contract`() {
		val declarables = MessagingTopology().oficinaTopology()

		val topic = declarables.getDeclarablesByType(TopicExchange::class.java).single()
		assertEquals("oficina", topic.name)
		assertTrue(topic.isDurable)
		val dlx = declarables.getDeclarablesByType(DirectExchange::class.java).single()
		assertEquals("oficina.dlx", dlx.name)
		val queues = declarables.getDeclarablesByType(Queue::class.java).associateBy { it.name }
		assertEquals(
			setOf("os.events", "os.events.dlq", "billing.commands", "billing.commands.dlq", "execution.commands", "execution.commands.dlq"),
			queues.keys,
		)
		listOf("os.events", "billing.commands", "execution.commands").forEach { name ->
			val queue = queues.getValue(name)
			assertTrue(queue.isDurable)
			assertFalse(queue.isExclusive)
			assertFalse(queue.isAutoDelete)
			assertEquals(mapOf("x-dead-letter-exchange" to "oficina.dlx", "x-dead-letter-routing-key" to "$name.dlq"), queue.arguments)
			assertTrue(queues.getValue("$name.dlq").arguments.isEmpty())
		}
		val bindings = bindingsOf(declarables)
		assertEquals("event.#", bindings["os.events"])
		assertEquals("command.billing.#", bindings["billing.commands"])
		assertEquals("command.execution.#", bindings["execution.commands"])
		assertEquals("os.events.dlq", bindings["os.events.dlq"])
	}

	private fun bindingsOf(declarables: Declarables) =
		declarables.getDeclarablesByType(org.springframework.amqp.core.Binding::class.java).associate { it.destination to it.routingKey }

	@Test
	fun `poison messages are not retried and other failures are`() {
		val poison = RuntimeException("listener failed", PoisonMessageException("bad"))
		assertFalse(ListenerRetryConfiguration.isRetryable(poison))
		assertTrue(ListenerRetryConfiguration.isRetryable(RuntimeException("listener failed", IllegalStateException("db"))))

		val settings = RetryPolicySettings()
		ListenerRetryConfiguration().poisonMessagesAreNotRetried().customize(settings)
		assertFalse(settings.exceptionPredicate!!.test(poison))
	}

	@Test
	fun `publisher sends the encoded command to the oficina exchange with the routing key of the type`() {
		val template = mockk<RabbitTemplate>(relaxed = true)
		val sent = slot<Message>()
		val sagaId = UUID.randomUUID()
		val publisher = RabbitCommandPublisher(template, codec, Clock.fixed(Instant.parse("2026-11-12T15:00:00Z"), ZoneOffset.UTC))

		publisher.publish(OutboundCommand(sagaId, MessageType.REQUEST_PAYMENT, "corr", RequestPaymentPayload(sagaId, sagaId, 10)))

		verify { template.send("oficina", "command.billing.request-payment", capture(sent)) }
		assertEquals("RequestPayment", sent.captured.messageProperties.type)
	}

	@Test
	fun `listener puts the correlation id in the MDC while the orchestrator runs`() {
		val orchestrator = mockk<SagaOrchestrator>()
		var mdcDuringHandling: String? = null
		every { orchestrator.handle(any()) } answers {
			mdcDuringHandling = MDC.get("requestId")
			SagaOutcome.APPLIED
		}
		val sagaId = UUID.randomUUID()
		val body = """{"messageId":"${UUID.randomUUID()}","sagaId":"$sagaId","type":"ExecutionFinished",
			"occurredAt":"2026-11-12T15:04:05Z","correlationId":"corr-xyz",
			"payload":{"workOrderId":"$sagaId","finishedAt":"2026-11-12T16:00:00Z"}}"""

		OsEventsListener(codec, orchestrator, tracing).onMessage(Message(body.toByteArray(), MessageProperties()))

		assertEquals("corr-xyz", mdcDuringHandling)
		assertNull(MDC.get("requestId"))
	}

	@Test
	fun `listener rethrows poison without calling the orchestrator`() {
		val orchestrator = mockk<SagaOrchestrator>()

		assertFailsWith<PoisonMessageException> {
			OsEventsListener(codec, orchestrator, tracing).onMessage(Message("{".toByteArray(), MessageProperties()))
		}
		verify(exactly = 0) { orchestrator.handle(any()) }
	}

	@Test
	fun `listener hands the trace headers of the message to the agent before processing and names the transaction`() {
		val orchestrator = mockk<SagaOrchestrator>()
		val mdcDuringHandling = mutableMapOf<String, String?>()
		every { orchestrator.handle(any()) } answers {
			// The headers were accepted before the orchestrator ran (it publishes inside the same trace).
			assertEquals(1, tracing.acceptedHeaders.size)
			listOf("requestId", "sagaId", "messageType", "messageId").forEach { mdcDuringHandling[it] = MDC.get(it) }
			SagaOutcome.APPLIED
		}
		val sagaId = UUID.randomUUID()
		val messageId = UUID.randomUUID()
		val body = """{"messageId":"$messageId","sagaId":"$sagaId","type":"QuoteApproved","occurredAt":"2026-11-12T15:04:05Z",
			"correlationId":"corr-trace","payload":{"workOrderId":"$sagaId","quoteId":"${UUID.randomUUID()}","totalCents":10}}"""
		val properties = MessageProperties().apply {
			this.messageId = messageId.toString()
			type = "QuoteApproved"
			consumerQueue = "os.events"
			setHeader("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
			setHeader("tracestate", "1@nr=0-0-1-2-3-4-1-1.0-0")
			setHeader("newrelic", "eyJ2IjpbMCwxXX0=")
			setHeader("x-correlation-id", "corr-trace")
		}

		OsEventsListener(codec, orchestrator, tracing).onMessage(Message(body.toByteArray(), properties))

		val headers = tracing.acceptedHeaders.single()
		assertEquals("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", headers["traceparent"])
		assertEquals("1@nr=0-0-1-2-3-4-1-1.0-0", headers["tracestate"])
		assertEquals("eyJ2IjpbMCwxXX0=", headers["newrelic"])
		assertEquals(listOf("Message/os.events/QuoteApproved"), tracing.transactionNames)
		assertEquals<Map<String, Any?>>(
			mapOf(
				"sagaId" to sagaId.toString(),
				"messageType" to "QuoteApproved",
				"messageId" to messageId.toString(),
				"correlationId" to "corr-trace",
			),
			tracing.attributes,
		)
		assertEquals<Map<String, Any?>>(
			mapOf("requestId" to "corr-trace", "sagaId" to sagaId.toString(), "messageType" to "QuoteApproved", "messageId" to messageId.toString()),
			mdcDuringHandling,
		)
		listOf("requestId", "sagaId", "messageType", "messageId").forEach { assertNull(MDC.get(it), it) }
	}

	@Test
	fun `poison message is still a named transaction and leaves the MDC clean`() {
		val properties = MessageProperties().apply {
			messageId = UUID.randomUUID().toString()
			type = "QuoteApproved"
		}

		assertFailsWith<PoisonMessageException> {
			OsEventsListener(codec, mockk<SagaOrchestrator>(), tracing).onMessage(Message("not json".toByteArray(), properties))
		}

		assertEquals(1, tracing.acceptedHeaders.size)
		assertEquals(listOf("Message/os.events/QuoteApproved"), tracing.transactionNames)
		assertNull(MDC.get("messageId"))
		assertNull(MDC.get("messageType"))

		OsEventsListener(codec, mockk<SagaOrchestrator>(), tracing).runCatching { onMessage(Message("{".toByteArray(), MessageProperties())) }
		assertEquals("Message/os.events/unknown", tracing.transactionNames.last())
	}
}
