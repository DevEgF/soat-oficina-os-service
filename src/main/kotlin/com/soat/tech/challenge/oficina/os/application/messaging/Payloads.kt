package com.soat.tech.challenge.oficina.os.application.messaging

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant
import java.util.UUID

// Payloads of the message contract. Money is always in cents; unknown fields are ignored on read.

data class ItemPayload(val code: String, val quantity: Int)

data class CustomerPayload(val name: String, val email: String? = null)

data class VehiclePayload(val plate: String, val brand: String, val model: String, val year: Int)

// ---- Commands (published by this service) ----

data class GenerateQuotePayload(
	val workOrderId: UUID,
	val trackingCode: String,
	val customer: CustomerPayload,
	val services: List<ItemPayload>,
	val parts: List<ItemPayload>,
)

data class RequestPaymentPayload(val workOrderId: UUID, val quoteId: UUID, val amountCents: Long)

data class CancelQuotePayload(val workOrderId: UUID, val quoteId: UUID, val reason: String)

data class RefundPaymentPayload(val workOrderId: UUID, val paymentId: UUID, val reason: String)

data class StartExecutionPayload(
	val workOrderId: UUID,
	val trackingCode: String,
	val customerName: String,
	val vehicle: VehiclePayload,
	val services: List<ItemPayload>,
	val parts: List<ItemPayload>,
)

// ---- Events (consumed from os.events) ----

@JsonIgnoreProperties(ignoreUnknown = true)
data class QuoteGeneratedPayload(
	val workOrderId: UUID,
	val quoteId: UUID,
	val servicesTotalCents: Long,
	val partsTotalCents: Long,
	val totalCents: Long,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class QuoteGenerationFailedPayload(val workOrderId: UUID, val reason: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class QuoteApprovedPayload(val workOrderId: UUID, val quoteId: UUID, val totalCents: Long)

@JsonIgnoreProperties(ignoreUnknown = true)
data class QuoteRejectedPayload(val workOrderId: UUID, val quoteId: UUID, val reason: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class QuoteCancelledPayload(val workOrderId: UUID, val quoteId: UUID)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PaymentApprovedPayload(
	val workOrderId: UUID,
	val quoteId: UUID,
	val paymentId: UUID,
	val amountCents: Long,
	val provider: String,
	val providerPaymentId: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PaymentRejectedPayload(val workOrderId: UUID, val quoteId: UUID, val paymentId: UUID, val reason: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PaymentRefundedPayload(val workOrderId: UUID, val paymentId: UUID)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExecutionStatusChangedPayload(val workOrderId: UUID, val status: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExecutionFinishedPayload(val workOrderId: UUID, val finishedAt: Instant)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExecutionFailedPayload(val workOrderId: UUID, val reason: String)

/** Free text describing why a failure event happened (logged until compensations exist). */
fun Any.failureReason(): String? = when (this) {
	is QuoteGenerationFailedPayload -> reason
	is QuoteRejectedPayload -> reason
	is PaymentRejectedPayload -> reason
	is ExecutionFailedPayload -> reason
	else -> null
}
