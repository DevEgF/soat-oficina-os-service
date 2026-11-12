package com.soat.tech.challenge.oficina.os.support

import com.soat.tech.challenge.oficina.os.domain.model.Customer
import com.soat.tech.challenge.oficina.os.domain.model.LicensePlate
import com.soat.tech.challenge.oficina.os.domain.model.StatusChange
import com.soat.tech.challenge.oficina.os.domain.model.TaxDocument
import com.soat.tech.challenge.oficina.os.domain.model.Vehicle
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrder
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrderSaga
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrderStatus
import com.soat.tech.challenge.oficina.os.domain.port.CustomerRepository
import com.soat.tech.challenge.oficina.os.domain.port.ProcessedMessageRepository
import com.soat.tech.challenge.oficina.os.domain.port.StatusHistoryRepository
import com.soat.tech.challenge.oficina.os.domain.port.VehicleRepository
import com.soat.tech.challenge.oficina.os.domain.port.WorkOrderRepository
import com.soat.tech.challenge.oficina.os.domain.port.WorkOrderSagaRepository
import java.time.Instant
import java.util.UUID

/** In-memory adapters of the persistence ports for unit tests of the application layer. */
class InMemoryPorts {
	val customers = mutableMapOf<UUID, Customer>()
	val vehicles = mutableMapOf<UUID, Vehicle>()
	val workOrders = mutableMapOf<UUID, WorkOrder>()
	val sagas = mutableMapOf<UUID, WorkOrderSaga>()
	val history = mutableListOf<StatusChange>()
	val processed = mutableSetOf<UUID>()

	val customerRepository = object : CustomerRepository {
		override fun save(customer: Customer) = customer.also { customers[it.id] = it }
		override fun findById(id: UUID) = customers[id]
		override fun findByTaxDocument(document: TaxDocument) = customers.values.firstOrNull { it.taxDocument == document }
	}

	val vehicleRepository = object : VehicleRepository {
		override fun save(vehicle: Vehicle) = vehicle.also { vehicles[it.id] = it }
		override fun findById(id: UUID) = vehicles[id]
		override fun findByLicensePlate(plate: LicensePlate) = vehicles.values.firstOrNull { it.licensePlate == plate }
	}

	val workOrderRepository = object : WorkOrderRepository {
		override fun save(workOrder: WorkOrder) = workOrder.also { workOrders[it.id] = it }
		override fun findById(id: UUID) = workOrders[id]
		override fun findAwaitingApprovalSince(quotedBefore: Instant, limit: Int) = workOrders.values
			.filter { it.status == WorkOrderStatus.PENDING_APPROVAL && it.quotedAt?.isBefore(quotedBefore) == true }
			.sortedBy { it.quotedAt }
			.take(limit)
			.map { it.id }
	}

	val sagaRepository = object : WorkOrderSagaRepository {
		override fun save(saga: WorkOrderSaga) = saga.also { sagas[it.id] = it }
		override fun findById(id: UUID) = sagas[id]
		override fun findByIdForUpdate(id: UUID) = sagas[id]
	}

	val historyRepository = object : StatusHistoryRepository {
		override fun append(change: StatusChange) {
			history += change
		}

		override fun findByWorkOrderId(workOrderId: UUID) = history.filter { it.workOrderId == workOrderId }
	}

	val processedRepository = object : ProcessedMessageRepository {
		override fun exists(messageId: UUID) = messageId in processed
		override fun record(messageId: UUID, type: String, sagaId: UUID, processedAt: Instant) {
			processed += messageId
		}
	}
}
