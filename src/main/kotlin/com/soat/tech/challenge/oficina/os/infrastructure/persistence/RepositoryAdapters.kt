package com.soat.tech.challenge.oficina.os.infrastructure.persistence

import com.soat.tech.challenge.oficina.os.domain.model.Customer
import com.soat.tech.challenge.oficina.os.domain.model.LicensePlate
import com.soat.tech.challenge.oficina.os.domain.model.StatusChange
import com.soat.tech.challenge.oficina.os.domain.model.TaxDocument
import com.soat.tech.challenge.oficina.os.domain.model.Vehicle
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrder
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrderSaga
import com.soat.tech.challenge.oficina.os.domain.port.CustomerRepository
import com.soat.tech.challenge.oficina.os.domain.port.ProcessedMessageRepository
import com.soat.tech.challenge.oficina.os.domain.port.StatusHistoryRepository
import com.soat.tech.challenge.oficina.os.domain.port.VehicleRepository
import com.soat.tech.challenge.oficina.os.domain.port.WorkOrderRepository
import com.soat.tech.challenge.oficina.os.domain.port.WorkOrderSagaRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class CustomerRepositoryAdapter(private val jpa: CustomerJpaRepository) : CustomerRepository {
	override fun save(customer: Customer): Customer = jpa.save(customer.toEntity()).toDomain()
	override fun findById(id: UUID): Customer? = jpa.findById(id.toString()).orElse(null)?.toDomain()
	override fun findByTaxDocument(document: TaxDocument): Customer? = jpa.findByDocumentDigits(document.digits)?.toDomain()
}

@Component
class VehicleRepositoryAdapter(private val jpa: VehicleJpaRepository) : VehicleRepository {
	override fun save(vehicle: Vehicle): Vehicle = jpa.save(vehicle.toEntity()).toDomain()
	override fun findById(id: UUID): Vehicle? = jpa.findById(id.toString()).orElse(null)?.toDomain()
	override fun findByLicensePlate(plate: LicensePlate): Vehicle? = jpa.findByLicensePlate(plate.normalized)?.toDomain()
}

@Component
class WorkOrderRepositoryAdapter(private val jpa: WorkOrderJpaRepository) : WorkOrderRepository {
	override fun save(workOrder: WorkOrder): WorkOrder {
		// Updates the managed entity so the @Version check protects concurrent transitions.
		val entity = jpa.findById(workOrder.id.toString()).orElseGet { WorkOrderEntity() }
		return jpa.saveAndFlush(workOrder.copyTo(entity)).toDomain()
	}

	override fun findById(id: UUID): WorkOrder? = jpa.findById(id.toString()).orElse(null)?.toDomain()
}

@Component
class WorkOrderSagaRepositoryAdapter(private val jpa: WorkOrderSagaJpaRepository) : WorkOrderSagaRepository {
	override fun save(saga: WorkOrderSaga): WorkOrderSaga = jpa.save(saga.toEntity()).toDomain()
	override fun findById(id: UUID): WorkOrderSaga? = jpa.findById(id.toString()).orElse(null)?.toDomain()
}

@Component
class StatusHistoryRepositoryAdapter(private val jpa: StatusHistoryJpaRepository) : StatusHistoryRepository {
	override fun append(change: StatusChange) {
		jpa.save(change.toEntity())
	}

	override fun findByWorkOrderId(workOrderId: UUID): List<StatusChange> =
		jpa.findByWorkOrderIdOrderByIdAsc(workOrderId.toString()).map { it.toDomain() }
}

@Component
class ProcessedMessageRepositoryAdapter(private val jpa: ProcessedMessageJpaRepository) : ProcessedMessageRepository {
	override fun exists(messageId: UUID): Boolean = jpa.existsById(messageId.toString())

	override fun record(messageId: UUID, type: String, sagaId: UUID, processedAt: Instant) {
		jpa.save(ProcessedMessageEntity(messageId.toString(), type, sagaId.toString(), processedAt))
	}
}
