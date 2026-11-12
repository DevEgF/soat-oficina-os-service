package com.soat.tech.challenge.oficina.os.application.saga

import com.soat.tech.challenge.oficina.os.application.messaging.AfterCommitPublisher
import com.soat.tech.challenge.oficina.os.application.messaging.ExecutionFailedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.ExecutionFinishedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.ExecutionStatusChangedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.GenerateQuotePayload
import com.soat.tech.challenge.oficina.os.application.messaging.IncomingMessage
import com.soat.tech.challenge.oficina.os.application.messaging.ItemPayload
import com.soat.tech.challenge.oficina.os.application.messaging.MessageType
import com.soat.tech.challenge.oficina.os.application.messaging.OutboundCommand
import com.soat.tech.challenge.oficina.os.application.messaging.PaymentApprovedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.PaymentRejectedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.QuoteApprovedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.QuoteGeneratedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.QuoteGenerationFailedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.QuoteRejectedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.StartExecutionPayload
import com.soat.tech.challenge.oficina.os.application.messaging.CustomerPayload
import com.soat.tech.challenge.oficina.os.application.messaging.RequestPaymentPayload
import com.soat.tech.challenge.oficina.os.domain.exception.NotFoundException
import com.soat.tech.challenge.oficina.os.domain.model.Customer
import com.soat.tech.challenge.oficina.os.domain.model.LicensePlate
import com.soat.tech.challenge.oficina.os.domain.model.PartLine
import com.soat.tech.challenge.oficina.os.domain.model.SagaStatus
import com.soat.tech.challenge.oficina.os.domain.model.SagaStep
import com.soat.tech.challenge.oficina.os.domain.model.ServiceLine
import com.soat.tech.challenge.oficina.os.domain.model.TaxDocument
import com.soat.tech.challenge.oficina.os.domain.model.Vehicle
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrder
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrderSaga
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrderStatus
import com.soat.tech.challenge.oficina.os.support.InMemoryPorts
import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SagaOrchestratorTest {

	private val now = Instant.parse("2026-11-12T15:00:00Z")
	private val ports = InMemoryPorts()
	private val afterCommit = mockk<AfterCommitPublisher>(relaxed = true)
	private val orchestrator = SagaOrchestrator(
		workOrders = ports.workOrderRepository,
		sagas = ports.sagaRepository,
		history = ports.historyRepository,
		processedMessages = ports.processedRepository,
		customers = ports.customerRepository,
		vehicles = ports.vehicleRepository,
		afterCommit = afterCommit,
		clock = Clock.fixed(now, ZoneOffset.UTC),
	)
	private val quoteId = UUID.randomUUID()
	private val paymentId = UUID.randomUUID()
	private lateinit var workOrder: WorkOrder

	@BeforeEach
	fun openWorkOrder() {
		val customer = Customer(UUID.randomUUID(), TaxDocument.parse("52998224725"), "Maria Silva", "maria@example.com")
		val vehicle = Vehicle(UUID.randomUUID(), customer.id, LicensePlate.parse("ABC1D23"), "Fiat", "Argo", 2022)
		ports.customerRepository.save(customer)
		ports.vehicleRepository.save(vehicle)
		workOrder = ports.workOrderRepository.save(
			WorkOrder.open(customer.id, vehicle.id, listOf(ServiceLine("TROCA_OLEO", 1)), listOf(PartLine("FILTRO_OLEO", 2)), now),
		)
		ports.sagaRepository.save(WorkOrderSaga.start(workOrder.id, "corr-123", now))
	}

	private fun event(type: MessageType, payload: Any, messageId: UUID = UUID.randomUUID(), sagaId: UUID = workOrder.id) =
		IncomingMessage(messageId, sagaId, type, now, "corr-123", payload)

	private fun quoteGenerated() = event(MessageType.QUOTE_GENERATED, QuoteGeneratedPayload(workOrder.id, quoteId, 12000, 9000, 21000))
	private fun quoteApproved(id: UUID = quoteId) = event(MessageType.QUOTE_APPROVED, QuoteApprovedPayload(workOrder.id, id, 21000))
	private fun paymentApproved() = event(
		MessageType.PAYMENT_APPROVED,
		PaymentApprovedPayload(workOrder.id, quoteId, paymentId, 21000, "SIMULATED", "sim-1"),
	)
	private fun executionStatus(status: String) =
		event(MessageType.EXECUTION_STATUS_CHANGED, ExecutionStatusChangedPayload(workOrder.id, status))
	private fun executionFinished() = event(MessageType.EXECUTION_FINISHED, ExecutionFinishedPayload(workOrder.id, now))

	private fun status() = ports.workOrders.getValue(workOrder.id).status
	private fun saga() = ports.sagas.getValue(workOrder.id)

	@Test
	fun `quote generated moves to PENDING_APPROVAL and keeps quote id and totals without a command`() {
		assertEquals(SagaOutcome.APPLIED, orchestrator.handle(quoteGenerated()))

		assertEquals(WorkOrderStatus.PENDING_APPROVAL, status())
		assertEquals(21000, ports.workOrders.getValue(workOrder.id).totalCents)
		assertEquals(quoteId, saga().quoteId)
		assertEquals(SagaStep.CUSTOMER_APPROVAL, saga().step)
		val change = ports.history.single()
		assertEquals(WorkOrderStatus.RECEIVED, change.fromStatus)
		assertEquals(WorkOrderStatus.PENDING_APPROVAL, change.toStatus)
		assertEquals("QuoteGenerated", change.reason)
		verify(exactly = 0) { afterCommit.publishAfterCommit(any()) }
	}

	@Test
	fun `quote approved moves to AWAITING_PAYMENT and requests the payment after commit`() {
		orchestrator.handle(quoteGenerated())
		val command = slot<OutboundCommand>()

		assertEquals(SagaOutcome.APPLIED, orchestrator.handle(quoteApproved()))

		assertEquals(WorkOrderStatus.AWAITING_PAYMENT, status())
		verify { afterCommit.publishAfterCommit(capture(command)) }
		assertEquals(MessageType.REQUEST_PAYMENT, command.captured.type)
		assertEquals("corr-123", command.captured.correlationId)
		assertEquals(RequestPaymentPayload(workOrder.id, quoteId, 21000), command.captured.payload)
	}

	@Test
	fun `payment approved moves to QUEUED and starts the execution with vehicle and items`() {
		orchestrator.handle(quoteGenerated())
		orchestrator.handle(quoteApproved())
		clearMocks(afterCommit)
		val command = slot<OutboundCommand>()

		assertEquals(SagaOutcome.APPLIED, orchestrator.handle(paymentApproved()))

		assertEquals(WorkOrderStatus.QUEUED, status())
		assertEquals(paymentId, saga().paymentId)
		assertEquals(SagaStep.EXECUTION, saga().step)
		verify { afterCommit.publishAfterCommit(capture(command)) }
		val payload = command.captured.payload as StartExecutionPayload
		assertEquals(MessageType.START_EXECUTION, command.captured.type)
		assertEquals("Maria Silva", payload.customerName)
		assertEquals("ABC1D23", payload.vehicle.plate)
		assertEquals(listOf(ItemPayload("TROCA_OLEO", 1)), payload.services)
		assertEquals(listOf(ItemPayload("FILTRO_OLEO", 2)), payload.parts)
		assertEquals(workOrder.trackingCode, payload.trackingCode)
	}

	@Test
	fun `execution events take the work order to FINALIZED with the full history`() {
		orchestrator.handle(quoteGenerated())
		orchestrator.handle(quoteApproved())
		orchestrator.handle(paymentApproved())
		orchestrator.handle(executionStatus("IN_DIAGNOSIS"))
		assertEquals(WorkOrderStatus.IN_DIAGNOSIS, status())
		orchestrator.handle(executionStatus("IN_EXECUTION"))
		assertEquals(WorkOrderStatus.IN_EXECUTION, status())
		orchestrator.handle(executionFinished())

		assertEquals(WorkOrderStatus.FINALIZED, status())
		assertEquals(SagaStep.DELIVERY, saga().step)
		assertEquals(SagaStatus.RUNNING, saga().status)
		assertEquals(
			listOf("QuoteGenerated", "QuoteApproved", "PaymentApproved", "ExecutionStatusChanged", "ExecutionStatusChanged", "ExecutionFinished"),
			ports.history.map { it.reason },
		)
	}

	@Test
	fun `repeated message id is ignored without a second effect`() {
		val event = quoteGenerated()
		orchestrator.handle(event)

		assertEquals(SagaOutcome.DUPLICATE, orchestrator.handle(event))
		assertEquals(1, ports.history.size)
	}

	@Test
	fun `event out of order is discarded without changing state`() {
		assertEquals(SagaOutcome.DISCARDED, orchestrator.handle(executionFinished()))

		assertEquals(WorkOrderStatus.RECEIVED, status())
		assertEquals(SagaStep.QUOTE_GENERATION, saga().step)
		assertEquals(0, ports.history.size)
		verify(exactly = 0) { afterCommit.publishAfterCommit(any()) }
	}

	@Test
	fun `approval of another quote is discarded`() {
		orchestrator.handle(quoteGenerated())

		assertEquals(SagaOutcome.DISCARDED, orchestrator.handle(quoteApproved(UUID.randomUUID())))
		assertEquals(WorkOrderStatus.PENDING_APPROVAL, status())
	}

	@Test
	fun `unexpected execution status is discarded`() {
		orchestrator.handle(quoteGenerated())
		orchestrator.handle(quoteApproved())
		orchestrator.handle(paymentApproved())

		assertEquals(SagaOutcome.DISCARDED, orchestrator.handle(executionStatus("FINISHED")))
		assertEquals(WorkOrderStatus.QUEUED, status())
	}

	@Test
	fun `event for an unknown work order is acknowledged and recorded`() {
		val unknown = UUID.randomUUID()
		val event = event(MessageType.QUOTE_GENERATED, QuoteGeneratedPayload(unknown, quoteId, 1, 1, 2), sagaId = unknown)

		assertEquals(SagaOutcome.UNKNOWN_SAGA, orchestrator.handle(event))
		assertEquals(SagaOutcome.DUPLICATE, orchestrator.handle(event))
	}

	@Test
	fun `failure events are only logged until the compensations exist`() {
		val failures = listOf(
			event(MessageType.QUOTE_GENERATION_FAILED, QuoteGenerationFailedPayload(workOrder.id, "Unknown code X")),
			event(MessageType.QUOTE_REJECTED, QuoteRejectedPayload(workOrder.id, quoteId, null)),
			event(MessageType.PAYMENT_REJECTED, PaymentRejectedPayload(workOrder.id, quoteId, paymentId, "card refused")),
			event(MessageType.EXECUTION_FAILED, ExecutionFailedPayload(workOrder.id, "engine")),
		)
		failures.forEach { assertEquals(SagaOutcome.NOT_HANDLED, orchestrator.handle(it)) }

		assertEquals(WorkOrderStatus.RECEIVED, status())
		assertEquals(0, ports.history.size)
		assertEquals(4, ports.processed.size)
	}

	@Test
	fun `a command routed to the events queue is ignored`() {
		val command = event(
			MessageType.GENERATE_QUOTE,
			GenerateQuotePayload(workOrder.id, "t", CustomerPayload("x"), emptyList(), emptyList()),
		)

		assertEquals(SagaOutcome.NOT_HANDLED, orchestrator.handle(command))
		confirmVerified(afterCommit)
	}

	@Test
	fun `missing customer while starting the execution is an error to retry`() {
		orchestrator.handle(quoteGenerated())
		orchestrator.handle(quoteApproved())
		ports.customers.clear()

		// In production the transaction rolls back and the listener retries, then dead-letters.
		assertFailsWith<NotFoundException> { orchestrator.handle(paymentApproved()) }
		verify(exactly = 1) { afterCommit.publishAfterCommit(any()) }
	}
}
