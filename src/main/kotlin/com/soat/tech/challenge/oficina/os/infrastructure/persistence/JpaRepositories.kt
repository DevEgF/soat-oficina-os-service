package com.soat.tech.challenge.oficina.os.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface CustomerJpaRepository : JpaRepository<CustomerEntity, String> {
	fun findByDocumentDigits(documentDigits: String): CustomerEntity?
}

interface VehicleJpaRepository : JpaRepository<VehicleEntity, String> {
	fun findByLicensePlate(licensePlate: String): VehicleEntity?
}

interface WorkOrderJpaRepository : JpaRepository<WorkOrderEntity, String>

interface WorkOrderSagaJpaRepository : JpaRepository<WorkOrderSagaEntity, String>

interface StatusHistoryJpaRepository : JpaRepository<StatusHistoryEntity, Long> {
	fun findByWorkOrderIdOrderByIdAsc(workOrderId: String): List<StatusHistoryEntity>
}

interface ProcessedMessageJpaRepository : JpaRepository<ProcessedMessageEntity, String>
