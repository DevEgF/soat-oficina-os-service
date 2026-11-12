package com.soat.tech.challenge.oficina.os.application.saga

import com.soat.tech.challenge.oficina.os.application.messaging.CustomerPayload
import com.soat.tech.challenge.oficina.os.application.messaging.GenerateQuotePayload
import com.soat.tech.challenge.oficina.os.application.messaging.ItemPayload
import com.soat.tech.challenge.oficina.os.application.messaging.MessageType
import com.soat.tech.challenge.oficina.os.application.messaging.OutboundCommand
import com.soat.tech.challenge.oficina.os.application.messaging.RequestPaymentPayload
import com.soat.tech.challenge.oficina.os.application.messaging.StartExecutionPayload
import com.soat.tech.challenge.oficina.os.application.messaging.VehiclePayload
import com.soat.tech.challenge.oficina.os.domain.model.Customer
import com.soat.tech.challenge.oficina.os.domain.model.Vehicle
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrder
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrderSaga
import java.util.UUID

/** Builds the commands of the saga from the aggregate (one place for the payload shape). */
object SagaCommands {

	fun generateQuote(workOrder: WorkOrder, customer: Customer, saga: WorkOrderSaga) = OutboundCommand(
		sagaId = workOrder.id,
		type = MessageType.GENERATE_QUOTE,
		correlationId = saga.correlationId,
		payload = GenerateQuotePayload(
			workOrderId = workOrder.id,
			trackingCode = workOrder.trackingCode,
			customer = CustomerPayload(name = customer.name, email = customer.email),
			services = workOrder.serviceLines.map { ItemPayload(it.code, it.quantity) },
			parts = workOrder.partLines.map { ItemPayload(it.code, it.quantity) },
		),
	)

	fun requestPayment(workOrder: WorkOrder, saga: WorkOrderSaga, quoteId: UUID, amountCents: Long) = OutboundCommand(
		sagaId = workOrder.id,
		type = MessageType.REQUEST_PAYMENT,
		correlationId = saga.correlationId,
		payload = RequestPaymentPayload(workOrderId = workOrder.id, quoteId = quoteId, amountCents = amountCents),
	)

	fun startExecution(workOrder: WorkOrder, customer: Customer, vehicle: Vehicle, saga: WorkOrderSaga) = OutboundCommand(
		sagaId = workOrder.id,
		type = MessageType.START_EXECUTION,
		correlationId = saga.correlationId,
		payload = StartExecutionPayload(
			workOrderId = workOrder.id,
			trackingCode = workOrder.trackingCode,
			customerName = customer.name,
			vehicle = VehiclePayload(
				plate = vehicle.licensePlate.normalized,
				brand = vehicle.brand,
				model = vehicle.model,
				year = vehicle.year,
			),
			services = workOrder.serviceLines.map { ItemPayload(it.code, it.quantity) },
			parts = workOrder.partLines.map { ItemPayload(it.code, it.quantity) },
		),
	)
}
