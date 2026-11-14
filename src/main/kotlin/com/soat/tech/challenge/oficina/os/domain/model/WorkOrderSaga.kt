package com.soat.tech.challenge.oficina.os.domain.model

import com.soat.tech.challenge.oficina.os.domain.exception.InvalidSagaStateException
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
 * Saga state of one work order (saga id = work order id). Keeps the ids returned by the participants,
 * the correlation id reused by every message of the saga and, after a failure, the compensations still
 * waiting for confirmation.
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
	failureReason: String? = null,
	pendingCompensations: Collection<Compensation> = emptySet(),
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

	/** Free text of the failure that started the compensation (null while nothing failed). */
	var failureReason: String? = failureReason
		private set

	private val pending: MutableSet<Compensation> = pendingCompensations.toMutableSet()

	/** Compensations requested and not confirmed yet, in a stable order. */
	val pendingCompensations: List<Compensation>
		get() = Compensation.entries.filter { it in pending }

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
		requireStatus(SagaStatus.RUNNING, "follow")
		SagaStep.of(workOrderStatus)?.let { step = it }
		if (workOrderStatus == WorkOrderStatus.DELIVERED) {
			status = SagaStatus.COMPLETED
		}
		updatedAt = now
	}

	/**
	 * The work order was cancelled by [cause]: the saga keeps the step where it stopped and waits for the
	 * confirmation of each compensation. Only what a participant actually did is undone (a quote or a
	 * payment the saga knows about). Returns the compensations to request, in publishing order.
	 */
	fun startCompensation(cause: CancellationCause, failureReason: String?, now: Instant): List<Compensation> {
		requireStatus(SagaStatus.RUNNING, "compensate(${cause.reason})")
		val required = cause.compensations.filter {
			when (it) {
				Compensation.CANCEL_QUOTE -> quoteId != null
				Compensation.REFUND_PAYMENT -> paymentId != null
			}
		}
		pending.clear()
		pending.addAll(required)
		this.failureReason = failureReason?.take(MAX_FAILURE_REASON_LENGTH)
		status = if (required.isEmpty()) SagaStatus.COMPENSATED else SagaStatus.COMPENSATING
		updatedAt = now
		return required
	}

	/** A participant confirmed [compensation]; the saga is COMPENSATED when nothing is left. */
	fun confirmCompensation(compensation: Compensation, now: Instant) {
		requireStatus(SagaStatus.COMPENSATING, "confirm($compensation)")
		if (!pending.remove(compensation)) {
			throw InvalidSagaStateException(status.name, "confirm($compensation) not pending")
		}
		if (pending.isEmpty()) {
			status = SagaStatus.COMPENSATED
		}
		updatedAt = now
	}

	private fun requireStatus(expected: SagaStatus, action: String) {
		if (status != expected) throw InvalidSagaStateException(status.name, action)
	}

	companion object {
		/** Size of the column; longer texts from the participants are cut. */
		const val MAX_FAILURE_REASON_LENGTH = 500

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
