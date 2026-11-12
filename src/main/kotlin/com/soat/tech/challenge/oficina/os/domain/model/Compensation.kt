package com.soat.tech.challenge.oficina.os.domain.model

/** Step already done by a participant that has to be undone when the saga fails. */
enum class Compensation {
	/** Billing must cancel the quote (QuoteCancelled confirms it). */
	CANCEL_QUOTE,

	/** Billing must refund the approved payment (PaymentRefunded confirms it). */
	REFUND_PAYMENT,
}

/**
 * Why a work order was cancelled. [reason] is what goes to the history, to the work order and to the
 * compensation commands; [allowedFrom] is the work order status the cause applies to; [compensations]
 * is what has to be undone, in the order the commands are published.
 */
enum class CancellationCause(
	val reason: String,
	val allowedFrom: Set<WorkOrderStatus>,
	val compensations: List<Compensation>,
) {
	QUOTE_GENERATION_FAILED("QuoteGenerationFailed", setOf(WorkOrderStatus.RECEIVED), emptyList()),
	QUOTE_REJECTED("QuoteRejected", setOf(WorkOrderStatus.PENDING_APPROVAL), emptyList()),
	APPROVAL_TIMEOUT("ApprovalTimeout", setOf(WorkOrderStatus.PENDING_APPROVAL), listOf(Compensation.CANCEL_QUOTE)),
	PAYMENT_REJECTED("PaymentRejected", setOf(WorkOrderStatus.AWAITING_PAYMENT), listOf(Compensation.CANCEL_QUOTE)),
	EXECUTION_FAILED(
		"ExecutionFailed",
		setOf(WorkOrderStatus.QUEUED, WorkOrderStatus.IN_DIAGNOSIS, WorkOrderStatus.IN_EXECUTION),
		listOf(Compensation.REFUND_PAYMENT, Compensation.CANCEL_QUOTE),
	),
}
