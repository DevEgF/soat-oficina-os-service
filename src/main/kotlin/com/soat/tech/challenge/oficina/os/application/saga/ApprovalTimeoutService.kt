package com.soat.tech.challenge.oficina.os.application.saga

import com.soat.tech.challenge.oficina.os.application.observability.addKeyValueUnlessInMdc
import com.soat.tech.challenge.oficina.os.domain.port.WorkOrderRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * Cancels the work orders waiting for the customer's approval longer than [SagaProperties.approvalTimeout].
 * Each work order is cancelled in its own transaction: a failure in one does not stop the others, and the
 * next run picks it up again.
 */
@Service
class ApprovalTimeoutService(
	private val workOrders: WorkOrderRepository,
	private val orchestrator: SagaOrchestrator,
	private val properties: SagaProperties,
	private val clock: Clock,
) {

	/** Returns how many work orders were cancelled in this run. */
	fun cancelOverdue(): Int {
		val deadline = properties.approvalTimeout
		val overdue = workOrders.findAwaitingApprovalSince(clock.instant().minus(deadline), BATCH_SIZE)
		var cancelled = 0
		overdue.forEach { id ->
			try {
				if (orchestrator.expireApproval(id, deadline)) cancelled++
			} catch (e: RuntimeException) {
				log.atError()
					.addKeyValueUnlessInMdc("sagaId", id)
					.addKeyValue("exceptionClass", e.javaClass.name)
					.addKeyValue("detail", e.message)
					.log("saga.approval_timeout.failed")
			}
		}
		if (cancelled > 0) {
			log.atInfo().addKeyValue("cancelled", cancelled).addKeyValue("deadline", deadline.toString()).log("saga.approval_timeout.run")
		}
		return cancelled
	}

	private companion object {
		/** Work orders handled per run; the rest waits for the next run. */
		const val BATCH_SIZE = 100
		val log = LoggerFactory.getLogger(ApprovalTimeoutService::class.java)
	}
}
