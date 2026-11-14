package com.soat.tech.challenge.oficina.os.domain.model

import com.soat.tech.challenge.oficina.os.domain.exception.InvalidSagaStateException
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkOrderSagaTest {

	private val t0 = Instant.parse("2026-11-12T10:00:00Z")

	@Test
	fun `saga starts running waiting for the quote`() {
		val id = UUID.randomUUID()
		val saga = WorkOrderSaga.start(id, "corr-1", t0)
		assertEquals(id, saga.id)
		assertEquals(SagaStatus.RUNNING, saga.status)
		assertEquals(SagaStep.QUOTE_GENERATION, saga.step)
		assertEquals("corr-1", saga.correlationId)
		assertNull(saga.quoteId)
		assertNull(saga.paymentId)
	}

	@Test
	fun `saga follows the work order and completes on delivery`() {
		val saga = WorkOrderSaga.start(UUID.randomUUID(), "corr", t0)
		val quoteId = UUID.randomUUID()
		val paymentId = UUID.randomUUID()
		saga.recordQuote(quoteId, t0.plusSeconds(1))
		saga.follow(WorkOrderStatus.PENDING_APPROVAL, t0.plusSeconds(1))
		assertEquals(SagaStep.CUSTOMER_APPROVAL, saga.step)
		saga.follow(WorkOrderStatus.AWAITING_PAYMENT, t0.plusSeconds(2))
		assertEquals(SagaStep.PAYMENT, saga.step)
		saga.recordPayment(paymentId, t0.plusSeconds(3))
		saga.follow(WorkOrderStatus.QUEUED, t0.plusSeconds(3))
		assertEquals(SagaStep.EXECUTION, saga.step)
		saga.follow(WorkOrderStatus.FINALIZED, t0.plusSeconds(4))
		assertEquals(SagaStep.DELIVERY, saga.step)
		assertEquals(SagaStatus.RUNNING, saga.status)
		saga.follow(WorkOrderStatus.DELIVERED, t0.plusSeconds(5))
		assertEquals(SagaStatus.COMPLETED, saga.status)
		assertEquals(quoteId, saga.quoteId)
		assertEquals(paymentId, saga.paymentId)
		assertEquals(t0.plusSeconds(5), saga.updatedAt)
		assertFailsWith<InvalidSagaStateException> { saga.follow(WorkOrderStatus.DELIVERED, t0) }
	}

	@Test
	fun `cancelled work order keeps the step where the saga stopped`() {
		val saga = WorkOrderSaga.start(UUID.randomUUID(), "corr", t0)
		saga.follow(WorkOrderStatus.PENDING_APPROVAL, t0)
		saga.follow(WorkOrderStatus.CANCELLED, t0)
		assertEquals(SagaStep.CUSTOMER_APPROVAL, saga.step)
	}

	@Test
	fun `every work order status maps to a step except cancelled`() {
		assertEquals(SagaStep.QUOTE_GENERATION, SagaStep.of(WorkOrderStatus.RECEIVED))
		assertEquals(SagaStep.EXECUTION, SagaStep.of(WorkOrderStatus.IN_DIAGNOSIS))
		assertEquals(SagaStep.EXECUTION, SagaStep.of(WorkOrderStatus.IN_EXECUTION))
		assertEquals(SagaStep.DELIVERY, SagaStep.of(WorkOrderStatus.DELIVERED))
		assertNull(SagaStep.of(WorkOrderStatus.CANCELLED))
	}

	private fun sagaWith(quote: Boolean, payment: Boolean) = WorkOrderSaga.start(UUID.randomUUID(), "corr", t0).apply {
		if (quote) recordQuote(UUID.randomUUID(), t0)
		if (payment) recordPayment(UUID.randomUUID(), t0)
		follow(WorkOrderStatus.QUEUED, t0)
	}

	@Test
	fun `failure without anything to undo is compensated at once`() {
		val saga = WorkOrderSaga.start(UUID.randomUUID(), "corr", t0)

		val required = saga.startCompensation(CancellationCause.QUOTE_GENERATION_FAILED, "Unknown code X", t0.plusSeconds(1))

		assertTrue(required.isEmpty())
		assertEquals(SagaStatus.COMPENSATED, saga.status)
		assertEquals(SagaStep.QUOTE_GENERATION, saga.step)
		assertEquals("Unknown code X", saga.failureReason)
		assertTrue(saga.pendingCompensations.isEmpty())
		assertEquals(t0.plusSeconds(1), saga.updatedAt)
	}

	@Test
	fun `execution failure waits for refund and quote cancellation in any order`() {
		listOf(
			listOf(Compensation.CANCEL_QUOTE, Compensation.REFUND_PAYMENT),
			listOf(Compensation.REFUND_PAYMENT, Compensation.CANCEL_QUOTE),
		).forEach { confirmations ->
			val saga = sagaWith(quote = true, payment = true)
			val required = saga.startCompensation(CancellationCause.EXECUTION_FAILED, "engine", t0)
			assertEquals(listOf(Compensation.REFUND_PAYMENT, Compensation.CANCEL_QUOTE), required)
			assertEquals(SagaStatus.COMPENSATING, saga.status)
			assertEquals(SagaStep.EXECUTION, saga.step)
			assertEquals(listOf(Compensation.CANCEL_QUOTE, Compensation.REFUND_PAYMENT), saga.pendingCompensations)

			saga.confirmCompensation(confirmations[0], t0.plusSeconds(1))
			assertEquals(SagaStatus.COMPENSATING, saga.status)
			assertEquals(listOf(confirmations[1]), saga.pendingCompensations)
			assertFailsWith<InvalidSagaStateException> { saga.confirmCompensation(confirmations[0], t0) }

			saga.confirmCompensation(confirmations[1], t0.plusSeconds(2))
			assertEquals(SagaStatus.COMPENSATED, saga.status)
			assertTrue(saga.pendingCompensations.isEmpty())
			assertEquals(t0.plusSeconds(2), saga.updatedAt)
			assertFailsWith<InvalidSagaStateException> { saga.confirmCompensation(confirmations[1], t0) }
		}
	}

	@Test
	fun `only what the saga knows about is compensated`() {
		val saga = sagaWith(quote = true, payment = false)

		assertEquals(listOf(Compensation.CANCEL_QUOTE), saga.startCompensation(CancellationCause.EXECUTION_FAILED, null, t0))
		assertNull(saga.failureReason)
	}

	@Test
	fun `compensation starts only once and only from a running saga`() {
		val saga = sagaWith(quote = true, payment = false)
		saga.startCompensation(CancellationCause.PAYMENT_REJECTED, "card", t0)

		assertFailsWith<InvalidSagaStateException> { saga.startCompensation(CancellationCause.PAYMENT_REJECTED, "card", t0) }
		assertFailsWith<InvalidSagaStateException> { saga.follow(WorkOrderStatus.QUEUED, t0) }
		val running = WorkOrderSaga.start(UUID.randomUUID(), "corr", t0)
		assertFailsWith<InvalidSagaStateException> { running.confirmCompensation(Compensation.CANCEL_QUOTE, t0) }
	}

	@Test
	fun `long failure reasons are cut to the column size`() {
		val saga = WorkOrderSaga.start(UUID.randomUUID(), "corr", t0)
		saga.startCompensation(CancellationCause.QUOTE_GENERATION_FAILED, "x".repeat(800), t0)
		assertEquals(WorkOrderSaga.MAX_FAILURE_REASON_LENGTH, saga.failureReason?.length)
	}
}
