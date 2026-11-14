package com.soat.tech.challenge.oficina.os.domain.model

import com.soat.tech.challenge.oficina.os.domain.exception.InvalidStatusTransitionException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Service requested for the work order, by catalog code (prices live in the Billing service). */
data class ServiceLine(val code: String, val quantity: Int) {
	init {
		require(code.isNotBlank()) { "Service code must not be blank" }
		require(quantity > 0) { "Service quantity must be positive" }
	}
}

/** Part requested for the work order, by catalog code (prices live in the Billing service). */
data class PartLine(val code: String, val quantity: Int) {
	init {
		require(code.isNotBlank()) { "Part code must not be blank" }
		require(quantity > 0) { "Part quantity must be positive" }
	}
}

/**
 * Work order (aggregate) and the state machine the saga drives.
 * Every transition validates the source status, as in the monolith.
 */
class WorkOrder(
	val id: UUID,
	val trackingCode: String,
	val customerId: UUID,
	val vehicleId: UUID,
	status: WorkOrderStatus,
	val serviceLines: List<ServiceLine>,
	val partLines: List<PartLine>,
	servicesTotalCents: Long? = null,
	partsTotalCents: Long? = null,
	totalCents: Long? = null,
	val createdAt: Instant,
	updatedAt: Instant = createdAt,
	quotedAt: Instant? = null,
	approvedAt: Instant? = null,
	paidAt: Instant? = null,
	diagnosisStartedAt: Instant? = null,
	repairStartedAt: Instant? = null,
	finishedAt: Instant? = null,
	deliveredAt: Instant? = null,
	cancelledAt: Instant? = null,
	cancellationReason: String? = null,
) {
	var status: WorkOrderStatus = status
		private set
	var servicesTotalCents: Long? = servicesTotalCents
		private set
	var partsTotalCents: Long? = partsTotalCents
		private set
	var totalCents: Long? = totalCents
		private set
	var updatedAt: Instant = updatedAt
		private set
	var quotedAt: Instant? = quotedAt
		private set
	var approvedAt: Instant? = approvedAt
		private set
	var paidAt: Instant? = paidAt
		private set
	var diagnosisStartedAt: Instant? = diagnosisStartedAt
		private set
	var repairStartedAt: Instant? = repairStartedAt
		private set
	var finishedAt: Instant? = finishedAt
		private set
	var deliveredAt: Instant? = deliveredAt
		private set
	var cancelledAt: Instant? = cancelledAt
		private set

	/** [CancellationCause.reason] of the cancellation, null while the work order is not cancelled. */
	var cancellationReason: String? = cancellationReason
		private set

	/**
	 * True when the work order has waited in PENDING_APPROVAL longer than [deadline]. The reference is
	 * the entry in PENDING_APPROVAL ([quotedAt]).
	 */
	fun approvalOverdue(deadline: Duration, now: Instant): Boolean {
		val since = quotedAt ?: return false
		return status == WorkOrderStatus.PENDING_APPROVAL && since.plus(deadline) < now
	}

	/** Billing priced the order: totals are stored as received (cents). */
	fun registerQuote(servicesTotalCents: Long, partsTotalCents: Long, totalCents: Long, now: Instant) {
		require(servicesTotalCents >= 0 && partsTotalCents >= 0 && totalCents >= 0) { "Quote totals cannot be negative" }
		moveFrom(WorkOrderStatus.RECEIVED, WorkOrderStatus.PENDING_APPROVAL, "registerQuote", now)
		this.servicesTotalCents = servicesTotalCents
		this.partsTotalCents = partsTotalCents
		this.totalCents = totalCents
		quotedAt = now
	}

	fun approveQuote(now: Instant) {
		moveFrom(WorkOrderStatus.PENDING_APPROVAL, WorkOrderStatus.AWAITING_PAYMENT, "approveQuote", now)
		approvedAt = now
	}

	fun confirmPayment(now: Instant) {
		moveFrom(WorkOrderStatus.AWAITING_PAYMENT, WorkOrderStatus.QUEUED, "confirmPayment", now)
		paidAt = now
	}

	fun startDiagnosis(now: Instant) {
		moveFrom(WorkOrderStatus.QUEUED, WorkOrderStatus.IN_DIAGNOSIS, "startDiagnosis", now)
		diagnosisStartedAt = now
	}

	fun startRepair(now: Instant) {
		moveFrom(WorkOrderStatus.IN_DIAGNOSIS, WorkOrderStatus.IN_EXECUTION, "startRepair", now)
		repairStartedAt = now
	}

	fun finish(now: Instant) {
		moveFrom(WorkOrderStatus.IN_EXECUTION, WorkOrderStatus.FINALIZED, "finish", now)
		finishedAt = now
	}

	fun registerDelivery(now: Instant) {
		moveFrom(WorkOrderStatus.FINALIZED, WorkOrderStatus.DELIVERED, "registerDelivery", now)
		deliveredAt = now
	}

	/** Compensation end state; each cause applies only to the status it can happen in. */
	fun cancel(cause: CancellationCause, now: Instant) {
		if (status !in cause.allowedFrom) {
			throw InvalidStatusTransitionException(status.name, "cancel(${cause.reason})")
		}
		status = WorkOrderStatus.CANCELLED
		updatedAt = now
		cancelledAt = now
		cancellationReason = cause.reason
	}

	private fun moveFrom(expected: WorkOrderStatus, target: WorkOrderStatus, action: String, now: Instant) {
		if (status != expected) {
			throw InvalidStatusTransitionException(status.name, action)
		}
		status = target
		updatedAt = now
	}

	companion object {
		fun open(
			customerId: UUID,
			vehicleId: UUID,
			serviceLines: List<ServiceLine>,
			partLines: List<PartLine>,
			now: Instant,
			id: UUID = UUID.randomUUID(),
			trackingCode: String = UUID.randomUUID().toString(),
		): WorkOrder {
			require(serviceLines.isNotEmpty() || partLines.isNotEmpty()) {
				"Work order needs at least one service or part"
			}
			return WorkOrder(
				id = id,
				trackingCode = trackingCode,
				customerId = customerId,
				vehicleId = vehicleId,
				status = WorkOrderStatus.RECEIVED,
				serviceLines = serviceLines,
				partLines = partLines,
				createdAt = now,
			)
		}
	}
}
