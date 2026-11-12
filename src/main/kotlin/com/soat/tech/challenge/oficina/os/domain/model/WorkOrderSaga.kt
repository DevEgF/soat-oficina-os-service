package com.soat.tech.challenge.oficina.os.domain.model

import java.time.Instant
import java.util.UUID

enum class SagaStatus { RUNNING, COMPLETED, COMPENSATING, COMPENSATED }

/** Saga step = which participant the orchestrator is waiting for. */
enum class SagaStep {
	QUOTE_GENERATION,
	CUSTOMER_APPROVAL,
	PAYMENT,
	EXECUTION,
	DELIVERY;

	companion object {
		/** Step implied by the work order status; CANCELLED keeps the step where the saga stopped. */
		fun of(status: WorkOrderStatus): SagaStep? = when (status) {
			WorkOrderStatus.RECEIVED -> QUOTE_GENERATION
			WorkOrderStatus.PENDING_APPROVAL -> CUSTOMER_APPROVAL
			WorkOrderStatus.AWAITING_PAYMENT -> PAYMENT
			WorkOrderStatus.QUEUED, WorkOrderStatus.IN_DIAGNOSIS, WorkOrderStatus.IN_EXECUTION -> EXECUTION
			WorkOrderStatus.FINALIZED, WorkOrderStatus.DELIVERED -> DELIVERY
			WorkOrderStatus.CANCELLED -> null
		}
	}
}

/**
 * Saga state of one work order (saga id = work order id). Keeps the ids returned by the participants
 * and the correlation id reused by every message of the saga.
 */
class WorkOrderSaga(
	val id: UUID,
	status: SagaStatus,
	step: SagaStep,
	quoteId: UUID? = null,
	paymentId: UUID? = null,
	val correlationId: String,
	val createdAt: Instant,
	updatedAt: Instant = createdAt,
) {
	var status: SagaStatus = status
		private set
	var step: SagaStep = step
		private set
	var quoteId: UUID? = quoteId
		private set
	var paymentId: UUID? = paymentId
		private set
	var updatedAt: Instant = updatedAt
		private set

	fun recordQuote(quoteId: UUID, now: Instant) {
		this.quoteId = quoteId
		updatedAt = now
	}

	fun recordPayment(paymentId: UUID, now: Instant) {
		this.paymentId = paymentId
		updatedAt = now
	}

	/** Follows the work order status; the saga completes on delivery. */
	fun follow(workOrderStatus: WorkOrderStatus, now: Instant) {
		check(status == SagaStatus.RUNNING) { "Saga $id is $status" }
		SagaStep.of(workOrderStatus)?.let { step = it }
		if (workOrderStatus == WorkOrderStatus.DELIVERED) {
			status = SagaStatus.COMPLETED
		}
		updatedAt = now
	}

	companion object {
		fun start(workOrderId: UUID, correlationId: String, now: Instant) = WorkOrderSaga(
			id = workOrderId,
			status = SagaStatus.RUNNING,
			step = SagaStep.QUOTE_GENERATION,
			correlationId = correlationId,
			createdAt = now,
		)
	}
}

/** One status change of a work order (history). [reason] is the event type or the HTTP action. */
data class StatusChange(
	val workOrderId: UUID,
	val fromStatus: WorkOrderStatus?,
	val toStatus: WorkOrderStatus,
	val reason: String,
	val occurredAt: Instant,
	val messageId: UUID? = null,
)
