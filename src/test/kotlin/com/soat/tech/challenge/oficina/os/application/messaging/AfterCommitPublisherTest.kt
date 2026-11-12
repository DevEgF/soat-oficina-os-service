package com.soat.tech.challenge.oficina.os.application.messaging

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AfterCommitPublisherTest {

	private val publisher = mockk<CommandPublisher>(relaxed = true)
	private val afterCommit = AfterCommitPublisher(publisher)
	private val sagaId = UUID.randomUUID()
	private val command = OutboundCommand(sagaId, MessageType.REQUEST_PAYMENT, "corr", RequestPaymentPayload(sagaId, UUID.randomUUID(), 100))

	@AfterEach
	fun clearSynchronization() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization()
		}
	}

	@Test
	fun `inside a transaction the command waits for the commit`() {
		TransactionSynchronizationManager.initSynchronization()

		afterCommit.publishAfterCommit(command)

		verify(exactly = 0) { publisher.publish(any()) }
		TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
		verify(exactly = 1) { publisher.publish(command) }
	}

	@Test
	fun `rollback never publishes`() {
		TransactionSynchronizationManager.initSynchronization()

		afterCommit.publishAfterCommit(command)

		TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCompletion(1) }
		verify(exactly = 0) { publisher.publish(any()) }
	}

	@Test
	fun `without a transaction the command is published at once and failures are swallowed`() {
		every { publisher.publish(any()) } throws IllegalStateException("broker down")

		afterCommit.publishAfterCommit(command)

		verify(exactly = 1) { publisher.publish(command) }
	}

	@Test
	fun `command must be a command type with the matching payload`() {
		assertFailsWith<IllegalArgumentException> {
			OutboundCommand(sagaId, MessageType.QUOTE_GENERATED, "c", QuoteGeneratedPayload(sagaId, sagaId, 1, 1, 2))
		}
		assertFailsWith<IllegalArgumentException> {
			OutboundCommand(sagaId, MessageType.REQUEST_PAYMENT, "c", CancelQuotePayload(sagaId, sagaId, "x"))
		}
	}

	@Test
	fun `message types resolve by name and failure reasons are extracted`() {
		assertEquals(MessageType.EXECUTION_FINISHED, MessageType.fromTypeName("ExecutionFinished"))
		assertNull(MessageType.fromTypeName("Bogus"))
		assertEquals(16, MessageType.entries.size)
		assertEquals("x", QuoteGenerationFailedPayload(sagaId, "x").failureReason())
		assertEquals("y", ExecutionFailedPayload(sagaId, "y").failureReason())
		assertNull(QuoteCancelledPayload(sagaId, sagaId).failureReason())
		assertNull(PaymentRefundedPayload(sagaId, sagaId).failureReason())
		assertEquals(RefundPaymentPayload(sagaId, sagaId, "r"), RefundPaymentPayload(sagaId, sagaId, "r"))
	}
}
