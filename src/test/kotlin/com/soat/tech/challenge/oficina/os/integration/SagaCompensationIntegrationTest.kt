package com.soat.tech.challenge.oficina.os.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Compensations driven from the edge: the failure event is published as Billing/Execution would, and the
 * test checks the work order over HTTP, the history and the compensation commands sent to Billing.
 */
class SagaCompensationIntegrationTest : SagaIntegrationTestSupport() {

	@Test
	fun `quote generation failure cancels the work order and closes the saga without commands`() {
		val id = openWorkOrder()
		awaitCommand(BILLING_TEST_QUEUE, "GenerateQuote", id)

		publishEvent(
			"QuoteGenerationFailed", "event.billing.quote-generation-failed", id,
			"""{"workOrderId":"$id","reason":"Unknown service code TROCA_MOTOR"}""",
		)

		val cancelled = awaitSagaStatus(id, "COMPENSATED")
		assertEquals("CANCELLED", cancelled["status"].asText())
		assertEquals("Cancelada", cancelled["statusLabel"].asText())
		assertEquals("QuoteGenerationFailed", cancelled["cancellationReason"].asText())
		assertEquals("QUOTE_GENERATION", cancelled["saga"]["step"].asText())
		assertEquals("Unknown service code TROCA_MOTOR", cancelled["saga"]["failureReason"].asText())
		assertEquals(emptyList<String>(), pendingCompensations(cancelled))
		assertEquals(Triple("RECEIVED", "CANCELLED", "QuoteGenerationFailed"), history(id).last())
		assertEquals(emptyList<String>(), awaitCommands(BILLING_TEST_QUEUE, id, emptyList(), settleMs = 1_000).map { it.type })
		assertEquals(409, post("/api/work-orders/$id/delivery", null, token).statusCode())
	}

	@Test
	fun `quote rejection cancels the work order and closes the saga without commands`() {
		val id = openWorkOrder()
		val quoteId = UUID.randomUUID().toString()
		quoteGenerated(id, quoteId)
		awaitStatus(id, "PENDING_APPROVAL")

		publishEvent("QuoteRejected", "event.billing.quote-rejected", id, """{"workOrderId":"$id","quoteId":"$quoteId","reason":"caro"}""")

		val cancelled = awaitSagaStatus(id, "COMPENSATED")
		assertEquals("CANCELLED", cancelled["status"].asText())
		assertEquals("QuoteRejected", cancelled["cancellationReason"].asText())
		assertEquals("CUSTOMER_APPROVAL", cancelled["saga"]["step"].asText())
		assertEquals("caro", cancelled["saga"]["failureReason"].asText())
		assertEquals(Triple("PENDING_APPROVAL", "CANCELLED", "QuoteRejected"), history(id).last())
		assertEquals(listOf("GenerateQuote"), awaitCommands(BILLING_TEST_QUEUE, id, listOf("GenerateQuote"), settleMs = 1_000).map { it.type })
	}

	@Test
	fun `payment rejection cancels the quote and the saga is compensated on QuoteCancelled`() {
		val id = openWorkOrder(correlationId = "itest-corr-payment-rejected")
		val quoteId = UUID.randomUUID().toString()
		val paymentId = UUID.randomUUID().toString()
		quoteGenerated(id, quoteId)
		awaitStatus(id, "PENDING_APPROVAL")
		quoteApproved(id, quoteId)
		awaitCommands(BILLING_TEST_QUEUE, id, listOf("GenerateQuote", "RequestPayment"))

		publishEvent(
			"PaymentRejected", "event.billing.payment-rejected", id,
			"""{"workOrderId":"$id","quoteId":"$quoteId","paymentId":"$paymentId","reason":"cc_rejected_other_reason"}""",
		)

		val compensating = awaitSagaStatus(id, "COMPENSATING")
		assertEquals("CANCELLED", compensating["status"].asText())
		assertEquals("PaymentRejected", compensating["cancellationReason"].asText())
		assertEquals("PAYMENT", compensating["saga"]["step"].asText())
		assertEquals("cc_rejected_other_reason", compensating["saga"]["failureReason"].asText())
		assertEquals(listOf("CANCEL_QUOTE"), pendingCompensations(compensating))
		assertEquals(paymentId, compensating["paymentId"].asText())
		assertEquals(Triple("AWAITING_PAYMENT", "CANCELLED", "PaymentRejected"), history(id).last())
		val cancelQuote = awaitCommand(BILLING_TEST_QUEUE, "CancelQuote", id)
		assertEquals("command.billing.cancel-quote", cancelQuote.routingKey)
		assertEquals("itest-corr-payment-rejected", cancelQuote.envelope["correlationId"].asText())
		assertEquals("itest-corr-payment-rejected", cancelQuote.properties.getHeader<String>("x-correlation-id"))
		assertEquals(id, cancelQuote.payload["workOrderId"].asText())
		assertEquals(quoteId, cancelQuote.payload["quoteId"].asText())
		assertEquals("PaymentRejected", cancelQuote.payload["reason"].asText())

		quoteCancelled(id, quoteId)
		val compensated = awaitSagaStatus(id, "COMPENSATED")
		assertEquals(emptyList<String>(), pendingCompensations(compensated))
		assertEquals("CANCELLED", compensated["status"].asText())
		// Billing confirms again (repeated CancelQuote): acked, nothing changes.
		awaitProcessed(quoteCancelled(id, quoteId))
		assertEquals("COMPENSATED", body(get("/api/work-orders/$id", token))["saga"]["status"].asText())
		assertEquals(1, history(id).count { it.second == "CANCELLED" })
	}

	@Test
	fun `execution failure while queued refunds and cancels, quote confirmation first`() {
		val (id, quoteId, paymentId) = queuedWorkOrder()

		val failed = publishEvent("ExecutionFailed", "event.execution.failed", id, """{"workOrderId":"$id","reason":"peça indisponível"}""")

		val compensating = awaitSagaStatus(id, "COMPENSATING")
		assertEquals("CANCELLED", compensating["status"].asText())
		assertEquals("ExecutionFailed", compensating["cancellationReason"].asText())
		assertEquals("EXECUTION", compensating["saga"]["step"].asText())
		assertEquals("peça indisponível", compensating["saga"]["failureReason"].asText())
		assertEquals(listOf("CANCEL_QUOTE", "REFUND_PAYMENT"), pendingCompensations(compensating))
		assertEquals(Triple("QUEUED", "CANCELLED", "ExecutionFailed"), history(id).last())

		// Duplicate (same message id) and a second failure (new id) are acked without a second compensation.
		publishEvent("ExecutionFailed", "event.execution.failed", id, """{"workOrderId":"$id","reason":"peça indisponível"}""", failed)
		awaitProcessed(publishEvent("ExecutionFailed", "event.execution.failed", id, """{"workOrderId":"$id","reason":"de novo"}"""))
		val commands = awaitCommands(BILLING_TEST_QUEUE, id, listOf("RefundPayment", "CancelQuote"), settleMs = 1_000)
			.filter { it.type in setOf("RefundPayment", "CancelQuote") }
		assertEquals(listOf("RefundPayment", "CancelQuote"), commands.map { it.type })
		assertEquals("command.billing.refund-payment", commands[0].routingKey)
		assertEquals(paymentId, commands[0].payload["paymentId"].asText())
		assertEquals("ExecutionFailed", commands[0].payload["reason"].asText())
		assertEquals(quoteId, commands[1].payload["quoteId"].asText())
		assertEquals("ExecutionFailed", commands[1].payload["reason"].asText())
		assertEquals(1, history(id).count { it.second == "CANCELLED" })

		quoteCancelled(id, quoteId)
		assertEquals(listOf("REFUND_PAYMENT"), pendingCompensations(awaitWorkOrder(id, "quote cancelled") { pendingCompensations(it) == listOf("REFUND_PAYMENT") }))
		assertEquals("COMPENSATING", body(get("/api/work-orders/$id", token))["saga"]["status"].asText())
		paymentRefunded(id, paymentId)
		assertEquals(emptyList<String>(), pendingCompensations(awaitSagaStatus(id, "COMPENSATED")))
	}

	@Test
	fun `execution failure during the repair is compensated with the refund confirmed first`() {
		val (id, quoteId, paymentId) = queuedWorkOrder()
		executionStatusChanged(id, "IN_DIAGNOSIS")
		awaitStatus(id, "IN_DIAGNOSIS")
		executionStatusChanged(id, "IN_EXECUTION")
		awaitStatus(id, "IN_EXECUTION")

		publishEvent("ExecutionFailed", "event.execution.failed", id, """{"workOrderId":"$id","reason":"motor"}""")
		awaitSagaStatus(id, "COMPENSATING")
		awaitCommands(BILLING_TEST_QUEUE, id, listOf("RefundPayment", "CancelQuote"))

		// Late execution event after the cancellation is out of order.
		awaitProcessed(publishEvent("ExecutionFinished", "event.execution.finished", id, """{"workOrderId":"$id","finishedAt":"2026-11-12T18:00:00Z"}"""))
		paymentRefunded(id, paymentId)
		awaitWorkOrder(id, "refund confirmed") { pendingCompensations(it) == listOf("CANCEL_QUOTE") }
		quoteCancelled(id, quoteId)

		val compensated = awaitSagaStatus(id, "COMPENSATED")
		assertEquals("CANCELLED", compensated["status"].asText())
		val transitions = history(id)
		assertEquals(Triple("IN_EXECUTION", "CANCELLED", "ExecutionFailed"), transitions.last())
		assertTrue(transitions.none { it.second == "FINALIZED" })
	}

	@Test
	fun `execution failure from diagnosis is compensated`() {
		val (id, _, _) = queuedWorkOrder()
		executionStatusChanged(id, "IN_DIAGNOSIS")
		awaitStatus(id, "IN_DIAGNOSIS")

		publishEvent("ExecutionFailed", "event.execution.failed", id, """{"workOrderId":"$id","reason":"motor"}""")

		awaitSagaStatus(id, "COMPENSATING")
		assertEquals(Triple("IN_DIAGNOSIS", "CANCELLED", "ExecutionFailed"), history(id).last())
		awaitCommands(BILLING_TEST_QUEUE, id, listOf("RefundPayment", "CancelQuote"))
	}

	/** Work order paid and waiting in the execution queue: (id, quoteId, paymentId). */
	private fun queuedWorkOrder(): Triple<String, String, String> {
		val id = openWorkOrder()
		val quoteId = UUID.randomUUID().toString()
		val paymentId = UUID.randomUUID().toString()
		quoteGenerated(id, quoteId)
		awaitStatus(id, "PENDING_APPROVAL")
		quoteApproved(id, quoteId)
		awaitStatus(id, "AWAITING_PAYMENT")
		paymentApproved(id, quoteId, paymentId)
		awaitStatus(id, "QUEUED")
		awaitCommand(EXECUTION_TEST_QUEUE, "StartExecution", id)
		awaitCommands(BILLING_TEST_QUEUE, id, listOf("GenerateQuote", "RequestPayment"))
		return Triple(id, quoteId, paymentId)
	}
}
