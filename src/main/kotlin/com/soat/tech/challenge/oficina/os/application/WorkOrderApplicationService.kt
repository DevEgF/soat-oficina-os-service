package com.soat.tech.challenge.oficina.os.application

import com.soat.tech.challenge.oficina.os.application.messaging.AfterCommitPublisher
import com.soat.tech.challenge.oficina.os.application.saga.SagaCommands
import com.soat.tech.challenge.oficina.os.domain.exception.BusinessRuleException
import com.soat.tech.challenge.oficina.os.domain.exception.NotFoundException
import com.soat.tech.challenge.oficina.os.domain.model.Customer
import com.soat.tech.challenge.oficina.os.domain.model.LicensePlate
import com.soat.tech.challenge.oficina.os.domain.model.PartLine
import com.soat.tech.challenge.oficina.os.domain.model.ServiceLine
import com.soat.tech.challenge.oficina.os.domain.model.StatusChange
import com.soat.tech.challenge.oficina.os.domain.model.TaxDocument
import com.soat.tech.challenge.oficina.os.domain.model.Vehicle
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrder
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrderSaga
import com.soat.tech.challenge.oficina.os.domain.port.CustomerRepository
import com.soat.tech.challenge.oficina.os.domain.port.StatusHistoryRepository
import com.soat.tech.challenge.oficina.os.domain.port.VehicleRepository
import com.soat.tech.challenge.oficina.os.domain.port.WorkOrderRepository
import com.soat.tech.challenge.oficina.os.domain.port.WorkOrderSagaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

data class OpenWorkOrderCommand(
	val customer: CustomerData,
	val vehicle: VehicleData,
	val services: List<ServiceLine>,
	val parts: List<PartLine>,
	val correlationId: String,
) {
	data class CustomerData(val taxId: String, val name: String, val email: String?, val phone: String?)
	data class VehicleData(val plate: String, val brand: String, val model: String, val year: Int)
}

/** Read model of a work order with everything the API shows. */
data class WorkOrderDetails(
	val workOrder: WorkOrder,
	val customer: Customer,
	val vehicle: Vehicle,
	val saga: WorkOrderSaga,
)

@Service
class WorkOrderApplicationService(
	private val workOrders: WorkOrderRepository,
	private val customers: CustomerRepository,
	private val vehicles: VehicleRepository,
	private val sagas: WorkOrderSagaRepository,
	private val history: StatusHistoryRepository,
	private val afterCommit: AfterCommitPublisher,
	private val clock: Clock,
) {

	/** Opens the work order (RECEIVED), starts the saga and asks Billing for the quote after the commit. */
	@Transactional
	fun open(command: OpenWorkOrderCommand): WorkOrderDetails {
		val now = clock.instant()
		val document = TaxDocument.parse(command.customer.taxId)
		val customer = customers.findByTaxDocument(document) ?: customers.save(
			Customer(
				id = UUID.randomUUID(),
				taxDocument = document,
				name = command.customer.name.trim(),
				email = command.customer.email.blankToNull(),
				phone = command.customer.phone.blankToNull(),
			),
		)
		val plate = LicensePlate.parse(command.vehicle.plate)
		val vehicle = vehicles.findByLicensePlate(plate) ?: vehicles.save(
			Vehicle(
				id = UUID.randomUUID(),
				customerId = customer.id,
				licensePlate = plate,
				brand = command.vehicle.brand.trim(),
				model = command.vehicle.model.trim(),
				year = command.vehicle.year,
			),
		)
		if (vehicle.customerId != customer.id) {
			throw BusinessRuleException("Vehicle belongs to another customer")
		}
		val workOrder = workOrders.save(
			WorkOrder.open(
				customerId = customer.id,
				vehicleId = vehicle.id,
				serviceLines = command.services,
				partLines = command.parts,
				now = now,
			),
		)
		val saga = sagas.save(WorkOrderSaga.start(workOrder.id, command.correlationId, now))
		history.append(StatusChange(workOrder.id, null, workOrder.status, REASON_OPENED, now))
		afterCommit.publishAfterCommit(SagaCommands.generateQuote(workOrder, customer, saga))
		log.atInfo()
			.addKeyValue("workOrderId", workOrder.id)
			.addKeyValue("sagaId", saga.id)
			.log("work_order.opened")
		return WorkOrderDetails(workOrder, customer, vehicle, saga)
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): WorkOrderDetails = details(load(id))

	@Transactional(readOnly = true)
	fun history(id: UUID): List<StatusChange> {
		load(id)
		return history.findByWorkOrderId(id)
	}

	/** Customer picked up the vehicle: FINALIZED -> DELIVERED and the saga is completed. */
	@Transactional
	fun registerDelivery(id: UUID): WorkOrderDetails {
		val now = clock.instant()
		val workOrder = load(id)
		val saga = loadSaga(id)
		val fromStatus = workOrder.status
		workOrder.registerDelivery(now)
		saga.follow(workOrder.status, now)
		workOrders.save(workOrder)
		sagas.save(saga)
		history.append(StatusChange(workOrder.id, fromStatus, workOrder.status, REASON_DELIVERED, now))
		log.atInfo().addKeyValue("workOrderId", id).log("work_order.delivered")
		return details(workOrder, saga)
	}

	private fun load(id: UUID): WorkOrder = workOrders.findById(id) ?: throw NotFoundException("Work order not found")

	private fun loadSaga(id: UUID): WorkOrderSaga = sagas.findById(id) ?: throw NotFoundException("Saga not found")

	private fun details(workOrder: WorkOrder, saga: WorkOrderSaga = loadSaga(workOrder.id)) = WorkOrderDetails(
		workOrder = workOrder,
		customer = customers.findById(workOrder.customerId) ?: throw NotFoundException("Customer not found"),
		vehicle = vehicles.findById(workOrder.vehicleId) ?: throw NotFoundException("Vehicle not found"),
		saga = saga,
	)

	private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

	companion object {
		const val REASON_OPENED = "WorkOrderOpened"
		const val REASON_DELIVERED = "DeliveryRegistered"
		private val log = LoggerFactory.getLogger(WorkOrderApplicationService::class.java)
	}
}
