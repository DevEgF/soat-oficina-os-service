package com.soat.tech.challenge.oficina.os.application.saga

import com.soat.tech.challenge.oficina.os.application.messaging.AfterCommitPublisher
import com.soat.tech.challenge.oficina.os.application.messaging.CancelQuotePayload
import com.soat.tech.challenge.oficina.os.application.messaging.PaymentRefundedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.QuoteCancelledPayload
import com.soat.tech.challenge.oficina.os.application.messaging.RefundPaymentPayload
import com.soat.tech.challenge.oficina.os.domain.model.Compensation
import java.time.Duration
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
import com.soat.tech.challenge.oficina.os.support.RecordingTracing
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
	private val tracing = RecordingTracing()
	private val lifecycle = SagaLifecycle(tracing)
	private val orchestrator = SagaOrchestrator(
		workOrders = ports.workOrderRepository,
		sagas = ports.sagaRepository,
		history = ports.historyRepository,
		processedMessages = ports.processedRepository,
		customers = ports.customerRepository,
		vehicles = ports.vehicleRepository,
		afterCommit = afterCommit,
		lifecycle = lifecycle,
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

	// ---- compensations ----

	private fun quoteGenerationFailed() =
		event(MessageType.QUOTE_GENERATION_FAILED, QuoteGenerationFailedPayload(workOrder.id, "Unknown code X"))
	private fun quoteRejected(id: UUID = quoteId) = event(MessageType.QUOTE_REJECTED, QuoteRejectedPayload(workOrder.id, id, "too expensive"))
	private fun paymentRejected() =
		event(MessageType.PAYMENT_REJECTED, PaymentRejectedPayload(workOrder.id, quoteId, paymentId, "cc_rejected_other_reason"))
	private fun executionFailed(messageId: UUID = UUID.randomUUID()) =
		event(MessageType.EXECUTION_FAILED, ExecutionFailedPayload(workOrder.id, "engine block cracked"), messageId)
	private fun quoteCancelled(id: UUID = quoteId) = event(MessageType.QUOTE_CANCELLED, QuoteCancelledPayload(workOrder.id, id))
	private fun paymentRefunded(id: UUID = paymentId) = event(MessageType.PAYMENT_REFUNDED, PaymentRefundedPayload(workOrder.id, id))

	private fun publishedCommands(): List<OutboundCommand> {
		val commands = mutableListOf<OutboundCommand>()
		verify(atLeast = 0) { afterCommit.publishAfterCommit(capture(commands)) }
		return commands
	}

	private fun lastChange() = ports.history.last()

	@Test
	fun `quote generation failure cancels the work order and compensates the saga at once`() {
		assertEquals(SagaOutcome.APPLIED, orchestrator.handle(quoteGenerationFailed()))

		assertEquals(WorkOrderStatus.CANCELLED, status())
		assertEquals("QuoteGenerationFailed", ports.workOrders.getValue(workOrder.id).cancellationReason)
		assertEquals(SagaStatus.COMPENSATED, saga().status)
		assertEquals(SagaStep.QUOTE_GENERATION, saga().step)
		assertEquals("Unknown code X", saga().failureReason)
		assertEquals(WorkOrderStatus.RECEIVED, lastChange().fromStatus)
		assertEquals(WorkOrderStatus.CANCELLED, lastChange().toStatus)
		assertEquals("QuoteGenerationFailed", lastChange().reason)
		assertNotNull(lastChange().messageId)
		verify(exactly = 0) { afterCommit.publishAfterCommit(any()) }
	}

	@Test
	fun `quote rejection cancels the work order without commands`() {
		orchestrator.handle(quoteGenerated())

		assertEquals(SagaOutcome.APPLIED, orchestrator.handle(quoteRejected()))

		assertEquals(WorkOrderStatus.CANCELLED, status())
		assertEquals(SagaStatus.COMPENSATED, saga().status)
		assertEquals(SagaStep.CUSTOMER_APPROVAL, saga().step)
		assertEquals("too expensive", saga().failureReason)
		assertEquals("QuoteRejected", lastChange().reason)
		verify(exactly = 0) { afterCommit.publishAfterCommit(any()) }
	}

	@Test
	fun `rejection of another quote or before the quote is discarded`() {
		assertEquals(SagaOutcome.DISCARDED, orchestrator.handle(quoteRejected()))
		orchestrator.handle(quoteGenerated())
		assertEquals(SagaOutcome.DISCARDED, orchestrator.handle(quoteRejected(UUID.randomUUID())))

		assertEquals(WorkOrderStatus.PENDING_APPROVAL, status())
		assertEquals(SagaStatus.RUNNING, saga().status)
	}

	@Test
	fun `payment rejection cancels the work order and asks Billing to cancel the quote`() {
		orchestrator.handle(quoteGenerated())
		orchestrator.handle(quoteApproved())
		clearMocks(afterCommit)

		assertEquals(SagaOutcome.APPLIED, orchestrator.handle(paymentRejected()))

		assertEquals(WorkOrderStatus.CANCELLED, status())
		assertEquals(SagaStatus.COMPENSATING, saga().status)
		assertEquals(SagaStep.PAYMENT, saga().step)
		assertEquals(listOf(Compensation.CANCEL_QUOTE), saga().pendingCompensations)
		assertEquals(paymentId, saga().paymentId)
		assertEquals("PaymentRejected", lastChange().reason)
		val command = publishedCommands().single()
		assertEquals(MessageType.CANCEL_QUOTE, command.type)
		assertEquals("corr-123", command.correlationId)
		assertEquals(CancelQuotePayload(workOrder.id, quoteId, "PaymentRejected"), command.payload)

		assertEquals(SagaOutcome.APPLIED, orchestrator.handle(quoteCancelled()))
		assertEquals(SagaStatus.COMPENSATED, saga().status)
		assertTrue(saga().pendingCompensations.isEmpty())
		assertEquals(3, ports.history.size, "confirmation does not change the work order status")
	}

	@Test
	fun `execution failure refunds the payment and cancels the quote from every execution status`() {
		listOf(null, "IN_DIAGNOSIS", "IN_EXECUTION").forEach { executionStatus ->
			openWorkOrder()
			orchestrator.handle(quoteGenerated())
			orchestrator.handle(quoteApproved())
			orchestrator.handle(paymentApproved())
			if (executionStatus != null) orchestrator.handle(executionStatus("IN_DIAGNOSIS"))
			if (executionStatus == "IN_EXECUTION") orchestrator.handle(executionStatus("IN_EXECUTION"))
			val from = status()
			clearMocks(afterCommit)

			assertEquals(SagaOutcome.APPLIED, orchestrator.handle(executionFailed()))

			assertEquals(WorkOrderStatus.CANCELLED, status())
			assertEquals(from, lastChange().fromStatus)
			assertEquals("ExecutionFailed", lastChange().reason)
			assertEquals(SagaStatus.COMPENSATING, saga().status)
			assertEquals(SagaStep.EXECUTION, saga().step)
			assertEquals("engine block cracked", saga().failureReason)
			assertEquals(listOf(Compensation.CANCEL_QUOTE, Compensation.REFUND_PAYMENT), saga().pendingCompensations)
			val commands = publishedCommands()
			assertEquals(listOf(MessageType.REFUND_PAYMENT, MessageType.CANCEL_QUOTE), commands.map { it.type })
			assertEquals(RefundPaymentPayload(workOrder.id, paymentId, "ExecutionFailed"), commands[0].payload)
			assertEquals(CancelQuotePayload(workOrder.id, quoteId, "ExecutionFailed"), commands[1].payload)
		}
	}

	@Test
	fun `confirmations close the compensation in any order`() {
		val cancelled: () -> IncomingMessage = { quoteCancelled() }
		val refunded: () -> IncomingMessage = { paymentRefunded() }
		listOf(listOf(cancelled, refunded), listOf(refunded, cancelled)).forEach { (first, second) ->
			openWorkOrder()
			orchestrator.handle(quoteGenerated())
			orchestrator.handle(quoteApproved())
			orchestrator.handle(paymentApproved())
			orchestrator.handle(executionFailed())

			assertEquals(SagaOutcome.APPLIED, orchestrator.handle(first()))
			assertEquals(SagaStatus.COMPENSATING, saga().status)
			assertEquals(1, saga().pendingCompensations.size)
			// Billing confirms again with a new message id: nothing left to confirm for that step.
			assertEquals(SagaOutcome.DISCARDED, orchestrator.handle(first()))

			assertEquals(SagaOutcome.APPLIED, orchestrator.handle(second()))
			assertEquals(SagaStatus.COMPENSATED, saga().status)
			assertTrue(saga().pendingCompensations.isEmpty())
			assertEquals(WorkOrderStatus.CANCELLED, status())
		}
	}

	@Test
	fun `duplicate or late execution failure does not compensate twice`() {
		orchestrator.handle(quoteGenerated())
		orchestrator.handle(quoteApproved())
		orchestrator.handle(paymentApproved())
		val messageId = UUID.randomUUID()
		orchestrator.handle(executionFailed(messageId))
		clearMocks(afterCommit)

		assertEquals(SagaOutcome.DUPLICATE, orchestrator.handle(executionFailed(messageId)))
		assertEquals(SagaOutcome.DISCARDED, orchestrator.handle(executionFailed()))
		assertEquals(SagaOutcome.DISCARDED, orchestrator.handle(executionStatus("IN_DIAGNOSIS")))

		assertEquals(WorkOrderStatus.CANCELLED, status())
		assertEquals(SagaStatus.COMPENSATING, saga().status)
		assertEquals(1, ports.history.count { it.toStatus == WorkOrderStatus.CANCELLED })
		verify(exactly = 0) { afterCommit.publishAfterCommit(any()) }
	}

	@Test
	fun `confirmations that do not match the saga are discarded`() {
		assertEquals(SagaOutcome.DISCARDED, orchestrator.handle(quoteCancelled()), "saga still running")
		orchestrator.handle(quoteGenerated())
		orchestrator.handle(quoteApproved())
		orchestrator.handle(paymentApproved())
		orchestrator.handle(executionFailed())

		assertEquals(SagaOutcome.DISCARDED, orchestrator.handle(quoteCancelled(UUID.randomUUID())))
		assertEquals(SagaOutcome.DISCARDED, orchestrator.handle(paymentRefunded(UUID.randomUUID())))
		assertEquals(listOf(Compensation.CANCEL_QUOTE, Compensation.REFUND_PAYMENT), saga().pendingCompensations)
	}

	@Test
	fun `failure events out of order are discarded`() {
		assertEquals(SagaOutcome.DISCARDED, orchestrator.handle(paymentRejected()))
		assertEquals(SagaOutcome.DISCARDED, orchestrator.handle(executionFailed()))
		orchestrator.handle(quoteGenerated())
		assertEquals(SagaOutcome.DISCARDED, orchestrator.handle(quoteGenerationFailed()))

		assertEquals(WorkOrderStatus.PENDING_APPROVAL, status())
		assertEquals(SagaStatus.RUNNING, saga().status)
		verify(exactly = 0) { afterCommit.publishAfterCommit(any()) }
	}

	@Test
	fun `execution failure after the work order is finalized is discarded`() {
		orchestrator.handle(quoteGenerated())
		orchestrator.handle(quoteApproved())
		orchestrator.handle(paymentApproved())
		orchestrator.handle(executionStatus("IN_DIAGNOSIS"))
		orchestrator.handle(executionStatus("IN_EXECUTION"))
		orchestrator.handle(executionFinished())

		assertEquals(SagaOutcome.DISCARDED, orchestrator.handle(executionFailed()))
		assertEquals(WorkOrderStatus.FINALIZED, status())
	}

	// ---- approval deadline ----

	private val deadline = Duration.ofMinutes(30)

	private fun orchestratorAt(instant: Instant) = SagaOrchestrator(
		workOrders = ports.workOrderRepository,
		sagas = ports.sagaRepository,
		history = ports.historyRepository,
		processedMessages = ports.processedRepository,
		customers = ports.customerRepository,
		vehicles = ports.vehicleRepository,
		afterCommit = afterCommit,
		lifecycle = lifecycle,
		clock = Clock.fixed(instant, ZoneOffset.UTC),
	)

	@Test
	fun `approval deadline cancels the work order and asks Billing to cancel the quote`() {
		orchestrator.handle(quoteGenerated())
		val later = orchestratorAt(now.plus(deadline).plusSeconds(1))

		assertTrue(later.expireApproval(workOrder.id, deadline))

		assertEquals(WorkOrderStatus.CANCELLED, status())
		assertEquals("ApprovalTimeout", ports.workOrders.getValue(workOrder.id).cancellationReason)
		assertEquals(SagaStatus.COMPENSATING, saga().status)
		assertEquals("Quote not approved within PT30M", saga().failureReason)
		assertEquals("ApprovalTimeout", lastChange().reason)
		assertNull(lastChange().messageId)
		assertEquals(CancelQuotePayload(workOrder.id, quoteId, "ApprovalTimeout"), publishedCommands().single().payload)

		// The customer's approval that arrives afterwards is out of order.
		assertEquals(SagaOutcome.DISCARDED, later.handle(quoteApproved()))
		assertEquals(WorkOrderStatus.CANCELLED, status())
		assertEquals(SagaOutcome.APPLIED, later.handle(quoteCancelled()))
		assertEquals(SagaStatus.COMPENSATED, saga().status)
	}

	@Test
	fun `approval deadline leaves alone what is not overdue anymore`() {
		orchestrator.handle(quoteGenerated())

		assertFalse(orchestratorAt(now.plus(deadline)).expireApproval(workOrder.id, deadline), "deadline not passed")
		orchestrator.handle(quoteApproved())
		assertFalse(orchestratorAt(now.plus(Duration.ofDays(1))).expireApproval(workOrder.id, deadline), "already approved")
		assertFalse(orchestrator.expireApproval(UUID.randomUUID(), deadline), "unknown work order")
		assertEquals(WorkOrderStatus.AWAITING_PAYMENT, status())
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

	// ---- telemetry ----

	@Test
	fun `every applied change is a SagaTransition and the end of the compensation a SagaFinished`() {
		orchestrator.handle(quoteGenerated())
		orchestrator.handle(quoteApproved())
		orchestrator.handle(paymentRejected())
		orchestratorAt(now.plusSeconds(90)).handle(quoteCancelled())

		val transitions = tracing.events(SagaLifecycle.TRANSITION_EVENT)
		assertEquals(listOf("QuoteGenerated", "QuoteApproved", "PaymentRejected", "QuoteCancelled"), transitions.map { it["trigger"] })
		assertEquals(
			mapOf(
				"sagaId" to workOrder.id.toString(),
				"fromStatus" to "AWAITING_PAYMENT",
				"toStatus" to "CANCELLED",
				"trigger" to "PaymentRejected",
				"sagaStatus" to "COMPENSATING",
			),
			transitions[2],
		)
		assertEquals("CANCELLED", transitions[3]["fromStatus"])
		assertEquals("COMPENSATED", transitions[3]["sagaStatus"])
		assertEquals(
			listOf(
				mapOf(
					"sagaId" to workOrder.id.toString(),
					"outcome" to "COMPENSATED",
					"durationMs" to 90_000L,
					"cancellationReason" to "PaymentRejected",
				),
			),
			tracing.events(SagaLifecycle.FINISHED_EVENT),
		)
	}

	@Test
	fun `discarded duplicate and ignored messages record no telemetry`() {
		val generated = quoteGenerated()
		orchestrator.handle(generated)
		tracing.events.clear()

		orchestrator.handle(generated)
		orchestrator.handle(executionFinished())
		orchestrator.handle(event(MessageType.GENERATE_QUOTE, GenerateQuotePayload(workOrder.id, "t", CustomerPayload("x"), emptyList(), emptyList())))

		assertTrue(tracing.events.isEmpty())
	}

	@Test
	fun `cancellation without anything to undo finishes the saga at once`() {
		orchestrator.handle(quoteGenerationFailed())

		assertEquals("QuoteGenerationFailed", tracing.events(SagaLifecycle.TRANSITION_EVENT).single()["trigger"])
		val finished = tracing.events(SagaLifecycle.FINISHED_EVENT).single()
		assertEquals("COMPENSATED", finished["outcome"])
		assertEquals("QuoteGenerationFailed", finished["cancellationReason"])
	}

	@Test
	fun `approval deadline is recorded with the ApprovalTimeout trigger`() {
		orchestrator.handle(quoteGenerated())
		tracing.events.clear()

		orchestratorAt(now.plus(deadline).plusSeconds(1)).expireApproval(workOrder.id, deadline)

		val transition = tracing.events(SagaLifecycle.TRANSITION_EVENT).single()
		assertEquals("ApprovalTimeout", transition["trigger"])
		assertEquals("PENDING_APPROVAL", transition["fromStatus"])
		assertEquals("COMPENSATING", transition["sagaStatus"])
		assertTrue(tracing.events(SagaLifecycle.FINISHED_EVENT).isEmpty())
	}
}
