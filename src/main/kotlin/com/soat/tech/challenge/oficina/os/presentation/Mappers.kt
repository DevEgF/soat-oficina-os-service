package com.soat.tech.challenge.oficina.os.presentation

import com.soat.tech.challenge.oficina.os.application.OpenWorkOrderCommand
import com.soat.tech.challenge.oficina.os.application.WorkOrderDetails
import com.soat.tech.challenge.oficina.os.domain.model.PartLine
import com.soat.tech.challenge.oficina.os.domain.model.ServiceLine
import com.soat.tech.challenge.oficina.os.domain.model.StatusChange
import com.soat.tech.challenge.oficina.os.presentation.dto.CreateWorkOrderRequest
import com.soat.tech.challenge.oficina.os.presentation.dto.CustomerResponse
import com.soat.tech.challenge.oficina.os.presentation.dto.ItemResponse
import com.soat.tech.challenge.oficina.os.presentation.dto.SagaResponse
import com.soat.tech.challenge.oficina.os.presentation.dto.StatusHistoryResponse
import com.soat.tech.challenge.oficina.os.presentation.dto.VehicleResponse
import com.soat.tech.challenge.oficina.os.presentation.dto.WorkOrderResponse

/** Bean Validation already ran on the request, so the required fields are present here. */
fun CreateWorkOrderRequest.toCommand(correlationId: String): OpenWorkOrderCommand {
	val customer = requireNotNull(customer) { "customer is required" }
	val vehicle = requireNotNull(vehicle) { "vehicle is required" }
	return OpenWorkOrderCommand(
		customer = OpenWorkOrderCommand.CustomerData(customer.taxId, customer.name, customer.email, customer.phone),
		vehicle = OpenWorkOrderCommand.VehicleData(
			plate = vehicle.plate,
			brand = vehicle.brand,
			model = vehicle.model,
			year = requireNotNull(vehicle.year) { "vehicle.year is required" },
		),
		services = services.map { ServiceLine(it.code.trim(), requireNotNull(it.quantity) { "quantity is required" }) },
		parts = parts.map { PartLine(it.code.trim(), requireNotNull(it.quantity) { "quantity is required" }) },
		correlationId = correlationId,
	)
}

fun WorkOrderDetails.toResponse(): WorkOrderResponse = WorkOrderResponse(
	id = workOrder.id,
	trackingCode = workOrder.trackingCode,
	status = workOrder.status.name,
	statusLabel = workOrder.status.label,
	customer = CustomerResponse(
		id = customer.id,
		taxId = customer.taxDocument.digits,
		name = customer.name,
		email = customer.email,
		phone = customer.phone,
	),
	vehicle = VehicleResponse(
		id = vehicle.id,
		plate = vehicle.licensePlate.normalized,
		brand = vehicle.brand,
		model = vehicle.model,
		year = vehicle.year,
	),
	services = workOrder.serviceLines.map { ItemResponse(it.code, it.quantity) },
	parts = workOrder.partLines.map { ItemResponse(it.code, it.quantity) },
	quoteId = saga.quoteId,
	paymentId = saga.paymentId,
	servicesTotalCents = workOrder.servicesTotalCents,
	partsTotalCents = workOrder.partsTotalCents,
	totalCents = workOrder.totalCents,
	cancellationReason = workOrder.cancellationReason,
	saga = SagaResponse(
		status = saga.status.name,
		step = saga.step.name,
		failureReason = saga.failureReason,
		pendingCompensations = saga.pendingCompensations.map { it.name },
	),
	createdAt = workOrder.createdAt,
	updatedAt = workOrder.updatedAt,
)

fun StatusChange.toResponse(): StatusHistoryResponse = StatusHistoryResponse(
	fromStatus = fromStatus?.name,
	toStatus = toStatus.name,
	reason = reason,
	occurredAt = occurredAt,
)
