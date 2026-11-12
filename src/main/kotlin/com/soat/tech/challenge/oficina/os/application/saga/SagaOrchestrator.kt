package com.soat.tech.challenge.oficina.os.application.saga

import com.soat.tech.challenge.oficina.os.application.messaging.AfterCommitPublisher
import com.soat.tech.challenge.oficina.os.application.messaging.ExecutionFailedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.ExecutionFinishedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.ExecutionStatusChangedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.IncomingMessage
import com.soat.tech.challenge.oficina.os.application.messaging.MessageKind
import com.soat.tech.challenge.oficina.os.application.messaging.MessageType
import com.soat.tech.challenge.oficina.os.application.messaging.OutboundCommand
import com.soat.tech.challenge.oficina.os.application.messaging.PaymentApprovedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.PaymentRefundedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.PaymentRejectedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.QuoteApprovedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.QuoteCancelledPayload
import com.soat.tech.challenge.oficina.os.application.messaging.QuoteGeneratedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.QuoteGenerationFailedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.QuoteRejectedPayload
import com.soat.tech.challenge.oficina.os.domain.exception.BusinessRuleException
import com.soat.tech.challenge.oficina.os.domain.exception.DomainException
import com.soat.tech.challenge.oficina.os.domain.exception.NotFoundException
import com.soat.tech.challenge.oficina.os.domain.model.CancellationCause
import com.soat.tech.challenge.oficina.os.domain.model.Compensation
import com.soat.tech.challenge.oficina.os.domain.model.StatusChange
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrder
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrderSaga
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrderStatus
import com.soat.tech.challenge.oficina.os.domain.port.CustomerRepository
import com.soat.tech.challenge.oficina.os.domain.port.ProcessedMessageRepository
import com.soat.tech.challenge.oficina.os.domain.port.StatusHistoryRepository
import com.soat.tech.challenge.oficina.os.domain.port.VehicleRepository
import com.soat.tech.challenge.oficina.os.domain.port.WorkOrderRepository
import com.soat.tech.challenge.oficina.os.domain.port.WorkOrderSagaRepository
import org.slf4j.LoggerFactory
import org.slf4j.spi.LoggingEventBuilder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What the orchestrator did with an event (logged and asserted in tests). */
enum class SagaOutcome { APPLIED, DUPLICATE, NOT_HANDLED, UNKNOWN_SAGA, DISCARDED }

/**
 * Saga orchestrator: receives the participants' events, applies the work order transition and decides
 * the next commands. Work order, saga, history and the processed message id are written in one
 * transaction; the commands go out only after the commit.
 *
 * Failure events (and the approval deadline) cancel the work order and start the compensation: the
 * saga goes to COMPENSATING while Billing still has to confirm QuoteCancelled and/or PaymentRefunded,
 * and to COMPENSATED when nothing is left. The saga row is locked while an event is applied, so the two
 * confirmations (or an event and the deadline check) never overwrite each other.
 */
@Service
class SagaOrchestrator(
	private val workOrders: WorkOrderRepository,
	private val sagas: WorkOrderSagaRepository,
	private val history: StatusHistoryRepository,
	private val processedMessages: ProcessedMessageRepository,
	private val customers: CustomerRepository,
	private val vehicles: VehicleRepository,
	private val afterCommit: AfterCommitPublisher,
	private val clock: Clock,
) {

	private fun interface Step {
		fun apply(workOrder: WorkOrder, saga: WorkOrderSaga, event: IncomingMessage, now: Instant): List<OutboundCommand>
	}

	private val steps: Map<MessageType, Step> = mapOf(
		MessageType.QUOTE_GENERATED to advance(::onQuoteGenerated),
		MessageType.QUOTE_APPROVED to advance(::onQuoteApproved),
		MessageType.PAYMENT_APPROVED to advance(::onPaymentApproved),
		MessageType.EXECUTION_STATUS_CHANGED to advance(::onExecutionStatusChanged),
		MessageType.EXECUTION_FINISHED to advance(::onExecutionFinished),
		MessageType.QUOTE_GENERATION_FAILED to Step(::onQuoteGenerationFailed),
		MessageType.QUOTE_REJECTED to Step(::onQuoteRejected),
		MessageType.PAYMENT_REJECTED to Step(::onPaymentRejected),
		MessageType.EXECUTION_FAILED to Step(::onExecutionFailed),
		MessageType.QUOTE_CANCELLED to Step(::onQuoteCancelled),
		MessageType.PAYMENT_REFUNDED to Step(::onPaymentRefunded),
	)

	@Transactional
	fun handle(event: IncomingMessage): SagaOutcome {
		if (processedMessages.exists(event.messageId)) {
			log(event, SagaOutcome.DUPLICATE).log("saga.event.duplicate")
			return SagaOutcome.DUPLICATE
		}
		val now = clock.instant()
		processedMessages.record(event.messageId, event.type.typeName, event.sagaId, now)

		val step = steps[event.type] ?: return notHandled(event)
		val saga = sagas.findByIdForUpdate(event.sagaId)
		val workOrder = saga?.let { workOrders.findById(it.id) }
		if (workOrder == null || saga == null) {
			warn(event, SagaOutcome.UNKNOWN_SAGA).log("saga.event.unknown_work_order")
			return SagaOutcome.UNKNOWN_SAGA
		}
		val fromStatus = workOrder.status
		val commands = try {
			step.apply(workOrder, saga, event, now)
		} catch (e: DomainException) {
			// Event that does not apply to the current state: discarded without changing anything.
			warn(event, SagaOutcome.DISCARDED)
				.addKeyValue("status", fromStatus.name)
				.addKeyValue("sagaStatus", saga.status.name)
				.addKeyValue("detail", e.message)
				.log("saga.event.out_of_order")
			return SagaOutcome.DISCARDED
		}
		persist(workOrder, saga, fromStatus, event.type.typeName, now, event.messageId)
		commands.forEach(afterCommit::publishAfterCommit)
		applied(log(event, SagaOutcome.APPLIED), workOrder, saga, fromStatus, commands).log("saga.event.applied")
		return SagaOutcome.APPLIED
	}

	/**
	 * Approval deadline: cancels the work order when it is still in PENDING_APPROVAL longer than
	 * [deadline] and asks Billing to cancel the quote. Re-checked under the saga lock, so an approval that
	 * arrived in the meantime wins. Returns true when the work order was cancelled.
	 */
	@Transactional
	fun expireApproval(workOrderId: UUID, deadline: Duration): Boolean {
		val now = clock.instant()
		val saga = sagas.findByIdForUpdate(workOrderId) ?: return false
		val workOrder = workOrders.findById(workOrderId) ?: return false
		if (!workOrder.approvalOverdue(deadline, now)) return false
		val fromStatus = workOrder.status
		val cause = CancellationCause.APPROVAL_TIMEOUT
		val commands = cancel(workOrder, saga, cause, "Quote not approved within $deadline", now)
		persist(workOrder, saga, fromStatus, cause.reason, now, messageId = null)
		commands.forEach(afterCommit::publishAfterCommit)
		applied(log.atInfo().addKeyValue("type", cause.reason).addKeyValue("sagaId", saga.id), workOrder, saga, fromStatus, commands)
			.log("saga.approval_timeout.applied")
		return true
	}

	// ---- happy path ----

	/** Happy path step: after the transition the saga follows the work order status. */
	private fun advance(step: Step) = Step { workOrder, saga, event, now ->
		step.apply(workOrder, saga, event, now).also { saga.follow(workOrder.status, now) }
	}

	private fun onQuoteGenerated(workOrder: WorkOrder, saga: WorkOrderSaga, event: IncomingMessage, now: Instant): List<OutboundCommand> {
		val payload = event.payload as QuoteGeneratedPayload
		workOrder.registerQuote(payload.servicesTotalCents, payload.partsTotalCents, payload.totalCents, now)
		saga.recordQuote(payload.quoteId, now)
		return emptyList()
	}

	private fun onQuoteApproved(workOrder: WorkOrder, saga: WorkOrderSaga, event: IncomingMessage, now: Instant): List<OutboundCommand> {
		val payload = event.payload as QuoteApprovedPayload
		requireSameQuote(saga, payload.quoteId)
		workOrder.approveQuote(now)
		return listOf(SagaCommands.requestPayment(workOrder, saga, payload.quoteId, payload.totalCents))
	}

	private fun onPaymentApproved(workOrder: WorkOrder, saga: WorkOrderSaga, event: IncomingMessage, now: Instant): List<OutboundCommand> {
		val payload = event.payload as PaymentApprovedPayload
		requireSameQuote(saga, payload.quoteId)
		workOrder.confirmPayment(now)
		saga.recordPayment(payload.paymentId, now)
		val customer = customers.findById(workOrder.customerId) ?: throw NotFoundException("Customer not found")
		val vehicle = vehicles.findById(workOrder.vehicleId) ?: throw NotFoundException("Vehicle not found")
		return listOf(SagaCommands.startExecution(workOrder, customer, vehicle, saga))
	}

	private fun onExecutionStatusChanged(
		workOrder: WorkOrder,
		saga: WorkOrderSaga,
		event: IncomingMessage,
		now: Instant,
	): List<OutboundCommand> {
		val payload = event.payload as ExecutionStatusChangedPayload
		when (payload.status) {
			STATUS_IN_DIAGNOSIS -> workOrder.startDiagnosis(now)
			STATUS_IN_EXECUTION -> workOrder.startRepair(now)
			else -> throw BusinessRuleException("Unexpected execution status ${payload.status}")
		}
		return emptyList()
	}

	private fun onExecutionFinished(workOrder: WorkOrder, saga: WorkOrderSaga, event: IncomingMessage, now: Instant): List<OutboundCommand> {
		event.payload as ExecutionFinishedPayload
		workOrder.finish(now)
		return emptyList()
	}

	// ---- compensations ----

	private fun onQuoteGenerationFailed(
		workOrder: WorkOrder,
		saga: WorkOrderSaga,
		event: IncomingMessage,
		now: Instant,
	): List<OutboundCommand> {
		val payload = event.payload as QuoteGenerationFailedPayload
		return cancel(workOrder, saga, CancellationCause.QUOTE_GENERATION_FAILED, payload.reason, now)
	}

	private fun onQuoteRejected(workOrder: WorkOrder, saga: WorkOrderSaga, event: IncomingMessage, now: Instant): List<OutboundCommand> {
		val payload = event.payload as QuoteRejectedPayload
		requireSameQuote(saga, payload.quoteId)
		return cancel(workOrder, saga, CancellationCause.QUOTE_REJECTED, payload.reason, now)
	}

	private fun onPaymentRejected(workOrder: WorkOrder, saga: WorkOrderSaga, event: IncomingMessage, now: Instant): List<OutboundCommand> {
		val payload = event.payload as PaymentRejectedPayload
		requireSameQuote(saga, payload.quoteId)
		val commands = cancel(workOrder, saga, CancellationCause.PAYMENT_REJECTED, payload.reason, now)
		// Kept for tracing the refused attempt in Billing; nothing to refund.
		saga.recordPayment(payload.paymentId, now)
		return commands
	}

	private fun onExecutionFailed(workOrder: WorkOrder, saga: WorkOrderSaga, event: IncomingMessage, now: Instant): List<OutboundCommand> {
		val payload = event.payload as ExecutionFailedPayload
		return cancel(workOrder, saga, CancellationCause.EXECUTION_FAILED, payload.reason, now)
	}

	private fun onQuoteCancelled(workOrder: WorkOrder, saga: WorkOrderSaga, event: IncomingMessage, now: Instant): List<OutboundCommand> {
		val payload = event.payload as QuoteCancelledPayload
		requireSameQuote(saga, payload.quoteId)
		saga.confirmCompensation(Compensation.CANCEL_QUOTE, now)
		return emptyList()
	}

	private fun onPaymentRefunded(workOrder: WorkOrder, saga: WorkOrderSaga, event: IncomingMessage, now: Instant): List<OutboundCommand> {
		val payload = event.payload as PaymentRefundedPayload
		if (saga.paymentId != payload.paymentId) throw BusinessRuleException("Event refers to another payment")
		saga.confirmCompensation(Compensation.REFUND_PAYMENT, now)
		return emptyList()
	}

	/** Cancels the work order and returns the commands that undo what the participants already did. */
	private fun cancel(
		workOrder: WorkOrder,
		saga: WorkOrderSaga,
		cause: CancellationCause,
		failureReason: String?,
		now: Instant,
	): List<OutboundCommand> {
		workOrder.cancel(cause, now)
		return saga.startCompensation(cause, failureReason, now).map { SagaCommands.compensate(it, saga, cause) }
	}

	// ---- shared ----

	private fun persist(workOrder: WorkOrder, saga: WorkOrderSaga, fromStatus: WorkOrderStatus, reason: String, now: Instant, messageId: UUID?) {
		workOrders.save(workOrder)
		sagas.save(saga)
		if (workOrder.status != fromStatus) {
			history.append(StatusChange(workOrder.id, fromStatus, workOrder.status, reason, now, messageId))
		}
	}

	private fun requireSameQuote(saga: WorkOrderSaga, quoteId: UUID) {
		if (saga.quoteId != quoteId) throw BusinessRuleException("Event refers to another quote")
	}

	/** Contract type this consumer does not react to (a command routed to os.events). */
	private fun notHandled(event: IncomingMessage): SagaOutcome {
		warn(event, SagaOutcome.NOT_HANDLED)
			.addKeyValue("kind", event.type.kind.name)
			.log(if (event.type.kind == MessageKind.COMMAND) "saga.command.ignored" else "saga.event.ignored")
		return SagaOutcome.NOT_HANDLED
	}

	private fun applied(
		builder: LoggingEventBuilder,
		workOrder: WorkOrder,
		saga: WorkOrderSaga,
		fromStatus: WorkOrderStatus,
		commands: List<OutboundCommand>,
	) = builder
		.addKeyValue("fromStatus", fromStatus.name)
		.addKeyValue("toStatus", workOrder.status.name)
		.addKeyValue("sagaStatus", saga.status.name)
		.addKeyValue("pendingCompensations", saga.pendingCompensations.joinToString(","))
		.addKeyValue("nextCommands", commands.joinToString(",") { it.type.typeName })

	private fun log(event: IncomingMessage, outcome: SagaOutcome) = keys(log.atInfo(), event, outcome)

	private fun warn(event: IncomingMessage, outcome: SagaOutcome) = keys(log.atWarn(), event, outcome)

	private fun keys(builder: LoggingEventBuilder, event: IncomingMessage, outcome: SagaOutcome) = builder
		.addKeyValue("type", event.type.typeName)
		.addKeyValue("sagaId", event.sagaId)
		.addKeyValue("messageId", event.messageId)
		.addKeyValue("outcome", outcome.name)

	private companion object {
		const val STATUS_IN_DIAGNOSIS = "IN_DIAGNOSIS"
		const val STATUS_IN_EXECUTION = "IN_EXECUTION"
		val log = LoggerFactory.getLogger(SagaOrchestrator::class.java)
	}
}
