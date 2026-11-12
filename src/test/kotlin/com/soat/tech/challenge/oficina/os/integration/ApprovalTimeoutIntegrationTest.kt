package com.soat.tech.challenge.oficina.os.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Approval deadline with a short duration. Own context (closed after the class), so the short deadline
 * never reaches the work orders of the other test classes.
 */
@TestPropertySource(properties = ["app.saga.approval-timeout=PT2S", "app.saga.approval-check-interval=PT0.5S"])
@DirtiesContext
class ApprovalTimeoutIntegrationTest : SagaIntegrationTestSupport() {

	@Test
	fun `work order waiting for approval past the deadline is cancelled and the quote cancellation requested`() {
		val id = openWorkOrder(correlationId = "itest-corr-timeout")
		val quoteId = UUID.randomUUID().toString()
		quoteGenerated(id, quoteId)
		val pending = awaitStatus(id, "PENDING_APPROVAL")
		val pendingSince = Instant.parse(pending["updatedAt"].asText())

		val cancelled = awaitStatus(id, "CANCELLED")
		assertTrue(
			Duration.between(pendingSince, Instant.parse(cancelled["updatedAt"].asText())) >= Duration.ofSeconds(2),
			"cancelled before the deadline",
		)
		assertEquals("ApprovalTimeout", cancelled["cancellationReason"].asText())
		assertEquals("COMPENSATING", cancelled["saga"]["status"].asText())
		assertEquals("CUSTOMER_APPROVAL", cancelled["saga"]["step"].asText())
		assertEquals("Quote not approved within PT2S", cancelled["saga"]["failureReason"].asText())
		assertEquals(listOf("CANCEL_QUOTE"), pendingCompensations(cancelled))
		assertEquals(Triple("PENDING_APPROVAL", "CANCELLED", "ApprovalTimeout"), history(id).last())
		val cancelQuote = awaitCommand(BILLING_TEST_QUEUE, "CancelQuote", id)
		assertEquals("command.billing.cancel-quote", cancelQuote.routingKey)
		assertEquals(quoteId, cancelQuote.payload["quoteId"].asText())
		assertEquals("ApprovalTimeout", cancelQuote.payload["reason"].asText())
		assertEquals("itest-corr-timeout", cancelQuote.envelope["correlationId"].asText())

		// The customer's approval arrives after the cancellation: acked and discarded, no payment requested.
		awaitProcessed(quoteApproved(id, quoteId))
		assertEquals("CANCELLED", body(get("/api/work-orders/$id", token))["status"].asText())

		quoteCancelled(id, quoteId)
		val compensated = awaitSagaStatus(id, "COMPENSATED")
		assertEquals(emptyList<String>(), pendingCompensations(compensated))
		assertEquals(1, history(id).count { it.second == "CANCELLED" })
		val later = awaitCommands(BILLING_TEST_QUEUE, id, emptyList(), settleMs = 1_500).map { it.type }
		assertEquals(emptyList<String>(), later, "no RequestPayment nor second CancelQuote")
	}

	@Test
	fun `work order approved before the deadline is not cancelled`() {
		val id = openWorkOrder()
		val quoteId = UUID.randomUUID().toString()
		quoteGenerated(id, quoteId)
		awaitStatus(id, "PENDING_APPROVAL")
		quoteApproved(id, quoteId)
		awaitStatus(id, "AWAITING_PAYMENT")

		Thread.sleep(3_000)

		val workOrder = body(get("/api/work-orders/$id", token))
		assertEquals("AWAITING_PAYMENT", workOrder["status"].asText())
		assertEquals("RUNNING", workOrder["saga"]["status"].asText())
		assertTrue(workOrder["cancellationReason"].isNull)
	}
}
