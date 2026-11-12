package com.soat.tech.challenge.oficina.os.domain.model

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
		assertFailsWith<IllegalStateException> { saga.follow(WorkOrderStatus.DELIVERED, t0) }
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
}
