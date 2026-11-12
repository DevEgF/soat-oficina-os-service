package com.soat.tech.challenge.oficina.os.infrastructure.messaging

import com.soat.tech.challenge.oficina.os.application.messaging.MessageType
import com.soat.tech.challenge.oficina.os.application.messaging.OutboundCommand
import com.soat.tech.challenge.oficina.os.application.messaging.RequestPaymentPayload
import com.soat.tech.challenge.oficina.os.application.saga.SagaOrchestrator
import com.soat.tech.challenge.oficina.os.application.saga.SagaOutcome
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

	@Test
	fun `topology declares exchanges queues dead letter queues and bindings of the spec`() {
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

		OsEventsListener(codec, orchestrator).onMessage(Message(body.toByteArray(), MessageProperties()))

		assertEquals("corr-xyz", mdcDuringHandling)
		assertNull(MDC.get("requestId"))
	}

	@Test
	fun `listener rethrows poison without calling the orchestrator`() {
		val orchestrator = mockk<SagaOrchestrator>()

		assertFailsWith<PoisonMessageException> {
			OsEventsListener(codec, orchestrator).onMessage(Message("{".toByteArray(), MessageProperties()))
		}
		verify(exactly = 0) { orchestrator.handle(any()) }
	}
}
