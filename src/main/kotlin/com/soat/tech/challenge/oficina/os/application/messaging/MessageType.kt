package com.soat.tech.challenge.oficina.os.application.messaging

import kotlin.reflect.KClass

enum class MessageKind { COMMAND, EVENT }

/**
 * Message contract shared by the three services (type, routing key and payload).
 * Commands are published only by this service; events come from Billing and Execution.
 */
enum class MessageType(
	val typeName: String,
	val routingKey: String,
	val kind: MessageKind,
	val payloadType: KClass<*>,
) {
	GENERATE_QUOTE("GenerateQuote", "command.billing.generate-quote", MessageKind.COMMAND, GenerateQuotePayload::class),
	REQUEST_PAYMENT("RequestPayment", "command.billing.request-payment", MessageKind.COMMAND, RequestPaymentPayload::class),
	CANCEL_QUOTE("CancelQuote", "command.billing.cancel-quote", MessageKind.COMMAND, CancelQuotePayload::class),
	REFUND_PAYMENT("RefundPayment", "command.billing.refund-payment", MessageKind.COMMAND, RefundPaymentPayload::class),
	START_EXECUTION("StartExecution", "command.execution.start-execution", MessageKind.COMMAND, StartExecutionPayload::class),
	QUOTE_GENERATED("QuoteGenerated", "event.billing.quote-generated", MessageKind.EVENT, QuoteGeneratedPayload::class),
	QUOTE_GENERATION_FAILED(
		"QuoteGenerationFailed", "event.billing.quote-generation-failed", MessageKind.EVENT, QuoteGenerationFailedPayload::class,
	),
	QUOTE_APPROVED("QuoteApproved", "event.billing.quote-approved", MessageKind.EVENT, QuoteApprovedPayload::class),
	QUOTE_REJECTED("QuoteRejected", "event.billing.quote-rejected", MessageKind.EVENT, QuoteRejectedPayload::class),
	QUOTE_CANCELLED("QuoteCancelled", "event.billing.quote-cancelled", MessageKind.EVENT, QuoteCancelledPayload::class),
	PAYMENT_APPROVED("PaymentApproved", "event.billing.payment-approved", MessageKind.EVENT, PaymentApprovedPayload::class),
	PAYMENT_REJECTED("PaymentRejected", "event.billing.payment-rejected", MessageKind.EVENT, PaymentRejectedPayload::class),
	PAYMENT_REFUNDED("PaymentRefunded", "event.billing.payment-refunded", MessageKind.EVENT, PaymentRefundedPayload::class),
	EXECUTION_STATUS_CHANGED(
		"ExecutionStatusChanged", "event.execution.status-changed", MessageKind.EVENT, ExecutionStatusChangedPayload::class,
	),
	EXECUTION_FINISHED("ExecutionFinished", "event.execution.finished", MessageKind.EVENT, ExecutionFinishedPayload::class),
	EXECUTION_FAILED("ExecutionFailed", "event.execution.failed", MessageKind.EVENT, ExecutionFailedPayload::class),
	;

	companion object {
		private val byTypeName = entries.associateBy { it.typeName }

		fun fromTypeName(typeName: String): MessageType? = byTypeName[typeName]
	}
}
