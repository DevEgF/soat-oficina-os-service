package com.soat.tech.challenge.oficina.os.presentation.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class LoginRequest(
	@field:NotBlank val username: String = "",
	@field:NotBlank val password: String = "",
)

data class LoginResponse(val accessToken: String, val expiresInSeconds: Long)

data class CustomerRequest(
	@field:NotBlank val taxId: String = "",
	@field:NotBlank @field:Size(max = 255) val name: String = "",
	@field:Size(max = 255) val email: String? = null,
	@field:Size(max = 50) val phone: String? = null,
)

data class VehicleRequest(
	@field:NotBlank val plate: String = "",
	@field:NotBlank @field:Size(max = 100) val brand: String = "",
	@field:NotBlank @field:Size(max = 100) val model: String = "",
	@field:NotNull @field:Min(1900) @field:Max(2100) val year: Int? = null,
)

data class ItemRequest(
	@field:NotBlank @field:Size(max = 100) val code: String = "",
	@field:NotNull @field:Positive val quantity: Int? = null,
)

data class CreateWorkOrderRequest(
	@field:NotNull @field:Valid val customer: CustomerRequest? = null,
	@field:NotNull @field:Valid val vehicle: VehicleRequest? = null,
	val services: List<@Valid ItemRequest> = emptyList(),
	val parts: List<@Valid ItemRequest> = emptyList(),
)

data class ItemResponse(val code: String, val quantity: Int)

data class CustomerResponse(
	val id: UUID,
	val taxId: String,
	val name: String,
	val email: String?,
	val phone: String?,
)

data class VehicleResponse(
	val id: UUID,
	val plate: String,
	val brand: String,
	val model: String,
	val year: Int,
)

data class SagaResponse(val status: String, val step: String)

data class WorkOrderResponse(
	val id: UUID,
	val trackingCode: String,
	val status: String,
	val statusLabel: String,
	val customer: CustomerResponse,
	val vehicle: VehicleResponse,
	val services: List<ItemResponse>,
	val parts: List<ItemResponse>,
	val quoteId: UUID?,
	val paymentId: UUID?,
	val servicesTotalCents: Long?,
	val partsTotalCents: Long?,
	val totalCents: Long?,
	val saga: SagaResponse,
	val createdAt: Instant,
	val updatedAt: Instant,
)

data class StatusHistoryResponse(
	val fromStatus: String?,
	val toStatus: String,
	val reason: String,
	val occurredAt: Instant,
)
