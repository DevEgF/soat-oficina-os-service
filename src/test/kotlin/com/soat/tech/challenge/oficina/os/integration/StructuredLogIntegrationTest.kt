package com.soat.tech.challenge.oficina.os.integration

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The structured (JSON) log of the cluster drops a line that writes the same name twice, so no line may
 * repeat as a key-value pair a key the MDC already carries (sagaId, messageType, messageId, requestId...).
 * Checked on every line logged while a saga runs through the HTTP API and the consumer.
 */
class StructuredLogIntegrationTest : SagaIntegrationTestSupport() {

	private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
	private val appender = ListAppender<ILoggingEvent>()

	@BeforeEach
	fun captureLogs() {
		appender.start()
		root.addAppender(appender)
	}

	@AfterEach
	fun releaseLogs() {
		root.detachAppender(appender)
	}

	private fun captured(): List<ILoggingEvent> = synchronized(appender) { appender.list.toList() }

	@Test
	fun `no log line repeats an MDC key and the saga lines carry the fixed keys`() {
		val id = openWorkOrder()
		val quoteId = UUID.randomUUID().toString()
		quoteGenerated(id, quoteId)
		awaitStatus(id, "PENDING_APPROVAL")
		quoteApproved(id, quoteId)
		awaitCommand(BILLING_TEST_QUEUE, "RequestPayment", id)
		publishEvent(
			"PaymentRejected", "event.billing.payment-rejected", id,
			"""{"workOrderId":"$id","quoteId":"$quoteId","paymentId":"${UUID.randomUUID()}","reason":"recusado"}""",
		)
		awaitCommand(BILLING_TEST_QUEUE, "CancelQuote", id)
		awaitProcessed(quoteCancelled(id, quoteId))
		awaitSagaStatus(id, "COMPENSATED")

		// Saga lines are written right after the commit, so the last one may trail the status by a moment.
		val deadline = System.currentTimeMillis() + TIMEOUT_MS
		while (captured().none { it.message == "saga.compensated" && fields(it)["sagaId"] == id } && System.currentTimeMillis() < deadline) {
			Thread.sleep(50)
		}
		val events = captured()
		events.forEach { event ->
			val repeated = event.keyValuePairs.orEmpty().map { it.key }.toSet() intersect event.mdcPropertyMap.keys
			assertTrue(repeated.isEmpty(), "${event.message} repeats $repeated")
		}
		val sagaLines = events.filter { it.loggerName.endsWith("SagaLifecycle") && fields(it)["sagaId"] == id }
		assertEquals(
			listOf("saga.started", "saga.step.completed", "saga.step.completed", "saga.compensation.started", "saga.compensation.confirmed", "saga.compensated"),
			sagaLines.map { it.message },
		)
		sagaLines.forEach { line ->
			assertTrue(fields(line).keys.containsAll(listOf("sagaId", "step", "fromStatus", "toStatus", "trigger", "sagaStatus")), line.message)
		}
		assertTrue("durationMs" in fields(sagaLines.last()))
		// Inside the consumer the MDC carries the message being processed.
		val consumed = events.first { it.message == "message.consumed" && it.mdcPropertyMap["sagaId"] == id }
		assertTrue(consumed.mdcPropertyMap.keys.containsAll(listOf("requestId", "sagaId", "messageType", "messageId")))
	}

	/** What the JSON line shows: MDC and key-value pairs together. */
	private fun fields(event: ILoggingEvent): Map<String, String?> =
		event.mdcPropertyMap + event.keyValuePairs.orEmpty().associate { it.key to it.value?.toString() }
}
