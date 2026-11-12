package com.soat.tech.challenge.oficina.os.infrastructure.persistence

import com.soat.tech.challenge.oficina.os.domain.model.Compensation
import com.soat.tech.challenge.oficina.os.domain.model.Customer
import com.soat.tech.challenge.oficina.os.domain.model.LicensePlate
import com.soat.tech.challenge.oficina.os.domain.model.PartLine
import com.soat.tech.challenge.oficina.os.domain.model.SagaStatus
import com.soat.tech.challenge.oficina.os.domain.model.SagaStep
import com.soat.tech.challenge.oficina.os.domain.model.ServiceLine
import com.soat.tech.challenge.oficina.os.domain.model.StatusChange
import com.soat.tech.challenge.oficina.os.domain.model.TaxDocument
import com.soat.tech.challenge.oficina.os.domain.model.Vehicle
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrder
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrderSaga
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrderStatus
import java.util.UUID

fun CustomerEntity.toDomain(): Customer = Customer(
	id = UUID.fromString(id),
	taxDocument = TaxDocument.parse(documentDigits),
	name = name,
	email = email,
	phone = phone,
)

fun Customer.toEntity(): CustomerEntity = CustomerEntity(
	id = id.toString(),
	documentDigits = taxDocument.digits,
	name = name,
	email = email,
	phone = phone,
)

fun VehicleEntity.toDomain(): Vehicle = Vehicle(
	id = UUID.fromString(id),
	customerId = UUID.fromString(customerId),
	licensePlate = LicensePlate.parse(licensePlate),
	brand = brand,
	model = model,
	year = year,
)

fun Vehicle.toEntity(): VehicleEntity = VehicleEntity(
	id = id.toString(),
	customerId = customerId.toString(),
	licensePlate = licensePlate.normalized,
	brand = brand,
	model = model,
	year = year,
)

fun WorkOrderEntity.toDomain(): WorkOrder = WorkOrder(
	id = UUID.fromString(id),
	trackingCode = trackingCode,
	customerId = UUID.fromString(customerId),
	vehicleId = UUID.fromString(vehicleId),
	status = WorkOrderStatus.valueOf(status),
	serviceLines = serviceLines.map { ServiceLine(it.code, it.quantity) },
	partLines = partLines.map { PartLine(it.code, it.quantity) },
	servicesTotalCents = servicesTotalCents,
	partsTotalCents = partsTotalCents,
	totalCents = totalCents,
	createdAt = createdAt,
	updatedAt = updatedAt,
	quotedAt = quotedAt,
	approvedAt = approvedAt,
	paidAt = paidAt,
	diagnosisStartedAt = diagnosisStartedAt,
	repairStartedAt = repairStartedAt,
	finishedAt = finishedAt,
	deliveredAt = deliveredAt,
	cancelledAt = cancelledAt,
	cancellationReason = cancellationReason,
)

/** Copies the aggregate onto a new or managed entity; lines are immutable after opening. */
fun WorkOrder.copyTo(entity: WorkOrderEntity): WorkOrderEntity = entity.also { e ->
	e.id = id.toString()
	e.trackingCode = trackingCode
	e.customerId = customerId.toString()
	e.vehicleId = vehicleId.toString()
	e.status = status.name
	e.servicesTotalCents = servicesTotalCents
	e.partsTotalCents = partsTotalCents
	e.totalCents = totalCents
	e.createdAt = createdAt
	e.updatedAt = updatedAt
	e.quotedAt = quotedAt
	e.approvedAt = approvedAt
	e.paidAt = paidAt
	e.diagnosisStartedAt = diagnosisStartedAt
	e.repairStartedAt = repairStartedAt
	e.finishedAt = finishedAt
	e.deliveredAt = deliveredAt
	e.cancelledAt = cancelledAt
	e.cancellationReason = cancellationReason
	if (e.serviceLines.isEmpty() && e.partLines.isEmpty()) {
		e.serviceLines.addAll(serviceLines.map { WorkOrderLineEmbeddable(it.code, it.quantity) })
		e.partLines.addAll(partLines.map { WorkOrderLineEmbeddable(it.code, it.quantity) })
	}
}

fun WorkOrderSagaEntity.toDomain(): WorkOrderSaga = WorkOrderSaga(
	id = UUID.fromString(id),
	status = SagaStatus.valueOf(status),
	step = SagaStep.valueOf(step),
	quoteId = quoteId?.let(UUID::fromString),
	paymentId = paymentId?.let(UUID::fromString),
	correlationId = correlationId,
	createdAt = createdAt,
	updatedAt = updatedAt,
	failureReason = failureReason,
	pendingCompensations = buildSet {
		if (pendingQuoteCancellation) add(Compensation.CANCEL_QUOTE)
		if (pendingPaymentRefund) add(Compensation.REFUND_PAYMENT)
	},
)

fun WorkOrderSaga.toEntity(): WorkOrderSagaEntity = WorkOrderSagaEntity(
	id = id.toString(),
	status = status.name,
	step = step.name,
	quoteId = quoteId?.toString(),
	paymentId = paymentId?.toString(),
	correlationId = correlationId,
	createdAt = createdAt,
	updatedAt = updatedAt,
	failureReason = failureReason,
	pendingQuoteCancellation = Compensation.CANCEL_QUOTE in pendingCompensations,
	pendingPaymentRefund = Compensation.REFUND_PAYMENT in pendingCompensations,
)

fun StatusHistoryEntity.toDomain(): StatusChange = StatusChange(
	workOrderId = UUID.fromString(workOrderId),
	fromStatus = fromStatus?.let(WorkOrderStatus::valueOf),
	toStatus = WorkOrderStatus.valueOf(toStatus),
	reason = reason,
	occurredAt = occurredAt,
	messageId = messageId?.let(UUID::fromString),
)

fun StatusChange.toEntity(): StatusHistoryEntity = StatusHistoryEntity(
	workOrderId = workOrderId.toString(),
	fromStatus = fromStatus?.name,
	toStatus = toStatus.name,
	reason = reason,
	messageId = messageId?.toString(),
	occurredAt = occurredAt,
)
