package com.soat.tech.challenge.oficina.os.application

import com.soat.tech.challenge.oficina.os.application.messaging.AfterCommitPublisher
import com.soat.tech.challenge.oficina.os.application.messaging.CustomerPayload
import com.soat.tech.challenge.oficina.os.application.messaging.GenerateQuotePayload
import com.soat.tech.challenge.oficina.os.application.messaging.ItemPayload
import com.soat.tech.challenge.oficina.os.application.messaging.MessageType
import com.soat.tech.challenge.oficina.os.application.messaging.OutboundCommand
import com.soat.tech.challenge.oficina.os.domain.exception.BusinessRuleException
import com.soat.tech.challenge.oficina.os.domain.exception.InvalidLicensePlateException
import com.soat.tech.challenge.oficina.os.domain.exception.InvalidStatusTransitionException
import com.soat.tech.challenge.oficina.os.domain.exception.InvalidTaxDocumentException
import com.soat.tech.challenge.oficina.os.domain.exception.NotFoundException
import com.soat.tech.challenge.oficina.os.domain.model.PartLine
import com.soat.tech.challenge.oficina.os.domain.model.SagaStatus
import com.soat.tech.challenge.oficina.os.domain.model.SagaStep
import com.soat.tech.challenge.oficina.os.domain.model.ServiceLine
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrderStatus
import com.soat.tech.challenge.oficina.os.support.InMemoryPorts
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorkOrderApplicationServiceTest {

	private val now = Instant.parse("2026-11-12T15:00:00Z")
	private val ports = InMemoryPorts()
	private val afterCommit = mockk<AfterCommitPublisher>(relaxed = true)
	private val service = WorkOrderApplicationService(
		workOrders = ports.workOrderRepository,
		customers = ports.customerRepository,
		vehicles = ports.vehicleRepository,
		sagas = ports.sagaRepository,
		history = ports.historyRepository,
		afterCommit = afterCommit,
		clock = Clock.fixed(now, ZoneOffset.UTC),
	)

	private fun command(
		taxId: String = "529.982.247-25",
		plate: String = "abc-1d23",
		services: List<ServiceLine> = listOf(ServiceLine("TROCA_OLEO", 1)),
		parts: List<PartLine> = listOf(PartLine("FILTRO_OLEO", 2)),
	) = OpenWorkOrderCommand(
		customer = OpenWorkOrderCommand.CustomerData(taxId, " Maria Silva ", "maria@example.com", " "),
		vehicle = OpenWorkOrderCommand.VehicleData(plate, "Fiat", "Argo", 2022),
		services = services,
		parts = parts,
		correlationId = "corr-open",
	)

	@Test
	fun `open creates customer vehicle work order saga and history and publishes GenerateQuote`() {
		val published = slot<OutboundCommand>()

		val details = service.open(command())

		assertEquals(WorkOrderStatus.RECEIVED, details.workOrder.status)
		assertEquals("52998224725", details.customer.taxDocument.digits)
		assertEquals("Maria Silva", details.customer.name)
		assertNull(details.customer.phone)
		assertEquals("ABC1D23", details.vehicle.licensePlate.normalized)
		assertEquals(SagaStatus.RUNNING, details.saga.status)
		assertEquals(SagaStep.QUOTE_GENERATION, details.saga.step)
		assertEquals("corr-open", details.saga.correlationId)
		val opened = ports.history.single()
		assertNull(opened.fromStatus)
		assertEquals(WorkOrderStatus.RECEIVED, opened.toStatus)
		assertEquals("WorkOrderOpened", opened.reason)
		verify { afterCommit.publishAfterCommit(capture(published)) }
		assertEquals(MessageType.GENERATE_QUOTE, published.captured.type)
		assertEquals(
			GenerateQuotePayload(
				workOrderId = details.workOrder.id,
				trackingCode = details.workOrder.trackingCode,
				customer = CustomerPayload("Maria Silva", "maria@example.com"),
				services = listOf(ItemPayload("TROCA_OLEO", 1)),
				parts = listOf(ItemPayload("FILTRO_OLEO", 2)),
			),
			published.captured.payload,
		)
	}

	@Test
	fun `open reuses customer and vehicle that already exist`() {
		val first = service.open(command())
		val second = service.open(command(taxId = "52998224725", plate = "ABC1D23"))

		assertEquals(first.customer.id, second.customer.id)
		assertEquals(first.vehicle.id, second.vehicle.id)
		assertEquals(1, ports.customers.size)
		assertEquals(1, ports.vehicles.size)
		assertEquals(2, ports.workOrders.size)
	}

	@Test
	fun `vehicle of another customer is a business rule violation`() {
		service.open(command())

		assertFailsWith<BusinessRuleException> { service.open(command(taxId = "11.222.333/0001-81")) }
	}

	@Test
	fun `invalid documents and empty orders are refused`() {
		assertFailsWith<InvalidTaxDocumentException> { service.open(command(taxId = "123")) }
		assertFailsWith<InvalidLicensePlateException> { service.open(command(plate = "12")) }
		assertFailsWith<IllegalArgumentException> { service.open(command(services = emptyList(), parts = emptyList())) }
	}

	@Test
	fun `get and history of an unknown work order are not found`() {
		assertFailsWith<NotFoundException> { service.get(UUID.randomUUID()) }
		assertFailsWith<NotFoundException> { service.history(UUID.randomUUID()) }
		assertFailsWith<NotFoundException> { service.registerDelivery(UUID.randomUUID()) }
	}

	@Test
	fun `delivery only from FINALIZED and then the saga completes`() {
		val id = service.open(command()).workOrder.id
		assertFailsWith<InvalidStatusTransitionException> { service.registerDelivery(id) }

		val workOrder = ports.workOrders.getValue(id).apply {
			registerQuote(1, 1, 2, now); approveQuote(now); confirmPayment(now)
			startDiagnosis(now); startRepair(now); finish(now)
		}
		ports.sagas.getValue(id).follow(workOrder.status, now)

		val delivered = service.registerDelivery(id)

		assertEquals(WorkOrderStatus.DELIVERED, delivered.workOrder.status)
		assertEquals(SagaStatus.COMPLETED, delivered.saga.status)
		assertEquals(SagaStep.DELIVERY, delivered.saga.step)
		assertEquals(listOf("WorkOrderOpened", "DeliveryRegistered"), service.history(id).map { it.reason })
		assertEquals(id, service.get(id).workOrder.id)
	}
}
