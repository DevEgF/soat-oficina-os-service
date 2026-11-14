package com.soat.tech.challenge.oficina.os.application.saga

import com.soat.tech.challenge.oficina.os.application.observability.Tracing
import com.soat.tech.challenge.oficina.os.application.observability.addKeyValueUnlessInMdc
import com.soat.tech.challenge.oficina.os.domain.model.SagaStatus
import com.soat.tech.challenge.oficina.os.domain.model.SagaStep
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrder
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrderSaga
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrderStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Work order and saga state before a change; tells which lifecycle event the change was. */
data class SagaSnapshot(val workOrderStatus: WorkOrderStatus, val sagaStatus: SagaStatus, val step: SagaStep) {
	companion object {
		fun of(workOrder: WorkOrder, saga: WorkOrderSaga) = SagaSnapshot(workOrder.status, saga.status, saga.step)
	}
}

/**
 * Lifecycle of the saga for operations: one structured log line per lifecycle event and the custom events
 * SagaTransition and SagaFinished in the APM. Everything is emitted after the commit (a rolled back change
 * leaves no trace) and carries no personal data.
 *
 * Log lines (fixed keys sagaId, step, fromStatus, toStatus, trigger, sagaStatus; durationMs when the saga
 * ends): saga.started, saga.step.completed, saga.compensation.started, saga.compensation.confirmed,
 * saga.compensated and saga.completed. [SagaSnapshot.step] is the step the change belongs to.
 */
@Component
class SagaLifecycle(private val tracing: Tracing) {

	/**
	 * Records one applied change of the saga. [before] is null when the saga was just started; [trigger] is
	 * the event type or the action (WorkOrderOpened, DeliveryRegistered, ApprovalTimeout).
	 */
	fun record(before: SagaSnapshot?, workOrder: WorkOrder, saga: WorkOrderSaga, trigger: String, now: Instant) {
		val change = Change(
			sagaId = saga.id,
			step = before?.step ?: saga.step,
			fromStatus = before?.workOrderStatus,
			toStatus = workOrder.status,
			trigger = trigger,
			fromSagaStatus = before?.sagaStatus,
			sagaStatus = saga.status,
			durationMs = Duration.between(workOrder.createdAt, now).toMillis(),
			cancellationReason = workOrder.cancellationReason,
		)
		afterCommit { emit(change) }
	}

	private fun emit(change: Change) {
		val events = lifecycleEvents(change.fromSagaStatus, change.sagaStatus)
		events.forEach { log(it, change) }
		runCatching {
			tracing.recordEvent(TRANSITION_EVENT, transitionAttributes(change))
			if (change.finished) tracing.recordEvent(FINISHED_EVENT, finishedAttributes(change))
		}.onFailure {
			// Telemetry never breaks the saga: the change is already committed.
			log.atWarn().addKeyValueUnlessInMdc("sagaId", change.sagaId).addKeyValue("exceptionClass", it.javaClass.name).log("saga.telemetry.failed")
		}
	}

	private fun log(event: String, change: Change) {
		var builder = log.atInfo()
			.addKeyValueUnlessInMdc("sagaId", change.sagaId)
			.addKeyValue("step", change.step.name)
			.addKeyValue("fromStatus", change.fromStatus?.name)
			.addKeyValue("toStatus", change.toStatus.name)
			.addKeyValue("trigger", change.trigger)
			.addKeyValue("sagaStatus", change.sagaStatus.name)
		if (event == COMPLETED || event == COMPENSATED) builder = builder.addKeyValue("durationMs", change.durationMs)
		builder.log(event)
	}

	private fun transitionAttributes(change: Change): Map<String, Any> = mapOf(
		"sagaId" to change.sagaId.toString(),
		"fromStatus" to (change.fromStatus?.name ?: ""),
		"toStatus" to change.toStatus.name,
		"trigger" to change.trigger,
		"sagaStatus" to change.sagaStatus.name,
	)

	private fun finishedAttributes(change: Change): Map<String, Any> = mapOf(
		"sagaId" to change.sagaId.toString(),
		"outcome" to change.sagaStatus.name,
		"durationMs" to change.durationMs,
		"cancellationReason" to (change.cancellationReason ?: ""),
	)

	private fun afterCommit(action: () -> Unit) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
				override fun afterCommit() = action()
			})
		} else {
			action()
		}
	}

	private data class Change(
		val sagaId: UUID,
		val step: SagaStep,
		val fromStatus: WorkOrderStatus?,
		val toStatus: WorkOrderStatus,
		val trigger: String,
		val fromSagaStatus: SagaStatus?,
		val sagaStatus: SagaStatus,
		val durationMs: Long,
		val cancellationReason: String?,
	) {
		/** The saga reached an end state with this change. */
		val finished: Boolean
			get() = sagaStatus in END_STATES && fromSagaStatus != sagaStatus
	}

	companion object {
		const val STARTED = "saga.started"
		const val STEP_COMPLETED = "saga.step.completed"
		const val COMPENSATION_STARTED = "saga.compensation.started"
		const val COMPENSATION_CONFIRMED = "saga.compensation.confirmed"
		const val COMPENSATED = "saga.compensated"
		const val COMPLETED = "saga.completed"

		/** Custom event types of the APM (a dashboard depends on these names and attributes). */
		const val TRANSITION_EVENT = "SagaTransition"
		const val FINISHED_EVENT = "SagaFinished"

		private val END_STATES = setOf(SagaStatus.COMPLETED, SagaStatus.COMPENSATED)
		private val log = LoggerFactory.getLogger(SagaLifecycle::class.java)

		/** Lifecycle events of a change, in order, from the saga status before ([from]) and after ([to]). */
		fun lifecycleEvents(from: SagaStatus?, to: SagaStatus): List<String> = when (from) {
			null -> listOf(STARTED)
			SagaStatus.RUNNING -> when (to) {
				SagaStatus.RUNNING -> listOf(STEP_COMPLETED)
				SagaStatus.COMPLETED -> listOf(STEP_COMPLETED, COMPLETED)
				SagaStatus.COMPENSATING -> listOf(COMPENSATION_STARTED)
				SagaStatus.COMPENSATED -> listOf(COMPENSATION_STARTED, COMPENSATED)
			}
			else -> if (to == SagaStatus.COMPENSATED) listOf(COMPENSATION_CONFIRMED, COMPENSATED) else listOf(COMPENSATION_CONFIRMED)
		}
	}
}
