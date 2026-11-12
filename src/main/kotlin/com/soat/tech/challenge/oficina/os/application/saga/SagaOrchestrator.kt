package com.soat.tech.challenge.oficina.os.application.saga

import com.soat.tech.challenge.oficina.os.application.messaging.AfterCommitPublisher
import com.soat.tech.challenge.oficina.os.application.messaging.ExecutionFinishedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.ExecutionStatusChangedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.IncomingMessage
import com.soat.tech.challenge.oficina.os.application.messaging.MessageKind
import com.soat.tech.challenge.oficina.os.application.messaging.MessageType
import com.soat.tech.challenge.oficina.os.application.messaging.OutboundCommand
import com.soat.tech.challenge.oficina.os.application.messaging.PaymentApprovedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.QuoteApprovedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.QuoteGeneratedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.failureReason
import com.soat.tech.challenge.oficina.os.domain.exception.BusinessRuleException
import com.soat.tech.challenge.oficina.os.domain.exception.DomainException
import com.soat.tech.challenge.oficina.os.domain.exception.NotFoundException
import com.soat.tech.challenge.oficina.os.domain.model.StatusChange
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrder
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrderSaga
import com.soat.tech.challenge.oficina.os.domain.port.CustomerRepository
import com.soat.tech.challenge.oficina.os.domain.port.ProcessedMessageRepository
import com.soat.tech.challenge.oficina.os.domain.port.StatusHistoryRepository
import com.soat.tech.challenge.oficina.os.domain.port.VehicleRepository
import com.soat.tech.challenge.oficina.os.domain.port.WorkOrderRepository
import com.soat.tech.challenge.oficina.os.domain.port.WorkOrderSagaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** What the orchestrator did with an event (logged and asserted in tests). */
enum class SagaOutcome { APPLIED, DUPLICATE, NOT_HANDLED, UNKNOWN_SAGA, DISCARDED }

/**
 * Saga orchestrator: receives the participants' events, applies the work order transition and decides
 * the next command. Work order, saga, history and the processed message id are written in one
 * transaction; the command goes out only after the commit.
 *
 * Today only the happy path is handled. Failure events are routed to [onCompensationTrigger], the single
 * place where the day-13 compensations plug in.
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
		fun apply(workOrder: WorkOrder, saga: WorkOrderSaga, event: IncomingMessage, now: Instant): OutboundCommand?
	}

	private val happyPath: Map<MessageType, Step> = mapOf(
		MessageType.QUOTE_GENERATED to Step(::onQuoteGenerated),
		MessageType.QUOTE_APPROVED to Step(::onQuoteApproved),
		MessageType.PAYMENT_APPROVED to Step(::onPaymentApproved),
		MessageType.EXECUTION_STATUS_CHANGED to Step(::onExecutionStatusChanged),
		MessageType.EXECUTION_FINISHED to Step(::onExecutionFinished),
	)

	/** Events whose reaction is a compensation. */
	private val compensationTriggers = setOf(
		MessageType.QUOTE_GENERATION_FAILED,
		MessageType.QUOTE_REJECTED,
		MessageType.QUOTE_CANCELLED,
		MessageType.PAYMENT_REJECTED,
		MessageType.PAYMENT_REFUNDED,
		MessageType.EXECUTION_FAILED,
	)

	@Transactional
	fun handle(event: IncomingMessage): SagaOutcome {
		if (processedMessages.exists(event.messageId)) {
			log(event, SagaOutcome.DUPLICATE).log("saga.event.duplicate")
			return SagaOutcome.DUPLICATE
		}
		val now = clock.instant()
		processedMessages.record(event.messageId, event.type.typeName, event.sagaId, now)

		val step = happyPath[event.type]
		if (step == null) {
			return if (event.type in compensationTriggers) onCompensationTrigger(event) else notHandled(event)
		}
		val workOrder = workOrders.findById(event.sagaId)
		val saga = sagas.findById(event.sagaId)
		if (workOrder == null || saga == null) {
			warn(event, SagaOutcome.UNKNOWN_SAGA).log("saga.event.unknown_work_order")
			return SagaOutcome.UNKNOWN_SAGA
		}
		val fromStatus = workOrder.status
		val next = try {
			step.apply(workOrder, saga, event, now)
		} catch (e: DomainException) {
			// Event that does not apply to the current state: discarded without changing anything.
			warn(event, SagaOutcome.DISCARDED)
				.addKeyValue("status", fromStatus.name)
				.addKeyValue("detail", e.message)
				.log("saga.event.out_of_order")
			return SagaOutcome.DISCARDED
		}
		saga.follow(workOrder.status, now)
		workOrders.save(workOrder)
		sagas.save(saga)
		history.append(StatusChange(workOrder.id, fromStatus, workOrder.status, event.type.typeName, now, event.messageId))
		next?.let(afterCommit::publishAfterCommit)
		log(event, SagaOutcome.APPLIED)
			.addKeyValue("fromStatus", fromStatus.name)
			.addKeyValue("toStatus", workOrder.status.name)
			.addKeyValue("nextCommand", next?.type?.typeName)
			.log("saga.event.applied")
		return SagaOutcome.APPLIED
	}

	private fun onQuoteGenerated(workOrder: WorkOrder, saga: WorkOrderSaga, event: IncomingMessage, now: Instant): OutboundCommand? {
		val payload = event.payload as QuoteGeneratedPayload
		workOrder.registerQuote(payload.servicesTotalCents, payload.partsTotalCents, payload.totalCents, now)
		saga.recordQuote(payload.quoteId, now)
		return null
	}

	private fun onQuoteApproved(workOrder: WorkOrder, saga: WorkOrderSaga, event: IncomingMessage, now: Instant): OutboundCommand {
		val payload = event.payload as QuoteApprovedPayload
		requireSameQuote(saga, payload.quoteId)
		workOrder.approveQuote(now)
		return SagaCommands.requestPayment(workOrder, saga, payload.quoteId, payload.totalCents)
	}

	private fun onPaymentApproved(workOrder: WorkOrder, saga: WorkOrderSaga, event: IncomingMessage, now: Instant): OutboundCommand {
		val payload = event.payload as PaymentApprovedPayload
		requireSameQuote(saga, payload.quoteId)
		workOrder.confirmPayment(now)
		saga.recordPayment(payload.paymentId, now)
		val customer = customers.findById(workOrder.customerId) ?: throw NotFoundException("Customer not found")
		val vehicle = vehicles.findById(workOrder.vehicleId) ?: throw NotFoundException("Vehicle not found")
		return SagaCommands.startExecution(workOrder, customer, vehicle, saga)
	}

	private fun onExecutionStatusChanged(workOrder: WorkOrder, saga: WorkOrderSaga, event: IncomingMessage, now: Instant): OutboundCommand? {
		val payload = event.payload as ExecutionStatusChangedPayload
		when (payload.status) {
			STATUS_IN_DIAGNOSIS -> workOrder.startDiagnosis(now)
			STATUS_IN_EXECUTION -> workOrder.startRepair(now)
			else -> throw BusinessRuleException("Unexpected execution status ${payload.status}")
		}
		return null
	}

	private fun onExecutionFinished(workOrder: WorkOrder, saga: WorkOrderSaga, event: IncomingMessage, now: Instant): OutboundCommand? {
		event.payload as ExecutionFinishedPayload
		workOrder.finish(now)
		return null
	}

	private fun requireSameQuote(saga: WorkOrderSaga, quoteId: java.util.UUID) {
		if (saga.quoteId != quoteId) throw BusinessRuleException("Event refers to another quote")
	}

	/**
	 * Failure of a participant. The compensations (cancel the work order, CancelQuote, RefundPayment)
	 * are not implemented yet; for now the event is only logged and acked.
	 */
	private fun onCompensationTrigger(event: IncomingMessage): SagaOutcome {
		warn(event, SagaOutcome.NOT_HANDLED)
			.addKeyValue("reason", event.payload.failureReason())
			.log("saga.compensation.not_implemented")
		return SagaOutcome.NOT_HANDLED
	}

	/** Contract type this consumer does not react to (for example a command routed to os.events). */
	private fun notHandled(event: IncomingMessage): SagaOutcome {
		warn(event, SagaOutcome.NOT_HANDLED)
			.addKeyValue("kind", event.type.kind.name)
			.log(if (event.type.kind == MessageKind.COMMAND) "saga.command.ignored" else "saga.event.ignored")
		return SagaOutcome.NOT_HANDLED
	}

	private fun log(event: IncomingMessage, outcome: SagaOutcome) = keys(log.atInfo(), event, outcome)

	private fun warn(event: IncomingMessage, outcome: SagaOutcome) = keys(log.atWarn(), event, outcome)

	private fun keys(builder: org.slf4j.spi.LoggingEventBuilder, event: IncomingMessage, outcome: SagaOutcome) = builder
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
