package com.soat.tech.challenge.oficina.os.domain.port

import com.soat.tech.challenge.oficina.os.domain.model.Customer
import com.soat.tech.challenge.oficina.os.domain.model.LicensePlate
import com.soat.tech.challenge.oficina.os.domain.model.StatusChange
import com.soat.tech.challenge.oficina.os.domain.model.TaxDocument
import com.soat.tech.challenge.oficina.os.domain.model.Vehicle
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrder
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrderSaga
import java.time.Instant
import java.util.UUID

interface CustomerRepository {
	fun save(customer: Customer): Customer
	fun findById(id: UUID): Customer?
	fun findByTaxDocument(document: TaxDocument): Customer?
}

interface VehicleRepository {
	fun save(vehicle: Vehicle): Vehicle
	fun findById(id: UUID): Vehicle?
	fun findByLicensePlate(plate: LicensePlate): Vehicle?
}

interface WorkOrderRepository {
	fun save(workOrder: WorkOrder): WorkOrder
	fun findById(id: UUID): WorkOrder?

	/** Ids of work orders in PENDING_APPROVAL since before [quotedBefore], oldest first, at most [limit]. */
	fun findAwaitingApprovalSince(quotedBefore: Instant, limit: Int): List<UUID>
}

interface WorkOrderSagaRepository {
	fun save(saga: WorkOrderSaga): WorkOrderSaga
	fun findById(id: UUID): WorkOrderSaga?

	/** Loads the saga holding a row lock until the transaction ends (one change of a saga at a time). */
	fun findByIdForUpdate(id: UUID): WorkOrderSaga?
}

interface StatusHistoryRepository {
	fun append(change: StatusChange)
	fun findByWorkOrderId(workOrderId: UUID): List<StatusChange>
}

/** Idempotency of the consumer: message ids already handled. */
interface ProcessedMessageRepository {
	fun exists(messageId: UUID): Boolean
	fun record(messageId: UUID, type: String, sagaId: UUID, processedAt: Instant)
}
