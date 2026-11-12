package com.soat.tech.challenge.oficina.os.infrastructure.persistence

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface CustomerJpaRepository : JpaRepository<CustomerEntity, String> {
	fun findByDocumentDigits(documentDigits: String): CustomerEntity?
}

interface VehicleJpaRepository : JpaRepository<VehicleEntity, String> {
	fun findByLicensePlate(licensePlate: String): VehicleEntity?
}

interface WorkOrderJpaRepository : JpaRepository<WorkOrderEntity, String> {
	@Query("select w.id from WorkOrderEntity w where w.status = :status and w.quotedAt < :quotedBefore order by w.quotedAt")
	fun findIdsByStatusQuotedBefore(status: String, quotedBefore: Instant, limit: Limit): List<String>
}

interface WorkOrderSagaJpaRepository : JpaRepository<WorkOrderSagaEntity, String> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from WorkOrderSagaEntity s where s.id = :id")
	fun findForUpdate(id: String): WorkOrderSagaEntity?
}

interface StatusHistoryJpaRepository : JpaRepository<StatusHistoryEntity, Long> {
	fun findByWorkOrderIdOrderByIdAsc(workOrderId: String): List<StatusHistoryEntity>
}

interface ProcessedMessageJpaRepository : JpaRepository<ProcessedMessageEntity, String>
