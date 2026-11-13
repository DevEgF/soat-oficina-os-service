package com.soat.tech.challenge.oficina.os.application.saga

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.soat.tech.challenge.oficina.os.application.observability.Tracing
import com.soat.tech.challenge.oficina.os.domain.model.CancellationCause
import com.soat.tech.challenge.oficina.os.domain.model.PartLine
import com.soat.tech.challenge.oficina.os.domain.model.SagaStatus
import com.soat.tech.challenge.oficina.os.domain.model.SagaStep
import com.soat.tech.challenge.oficina.os.domain.model.ServiceLine
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrder
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrderSaga
import com.soat.tech.challenge.oficina.os.domain.model.WorkOrderStatus
import com.soat.tech.challenge.oficina.os.support.RecordingTracing
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SagaLifecycleTest {

	private val opened = Instant.parse("2026-11-12T15:00:00Z")
	private val tracing = RecordingTracing()
	private val lifecycle = SagaLifecycle(tracing)
	private val appender = ListAppender<ILoggingEvent>()
	private val logger = LoggerFactory.getLogger(SagaLifecycle::class.java) as Logger

	private val workOrder = WorkOrder.open(UUID.randomUUID(), UUID.randomUUID(), listOf(ServiceLine("TROCA_OLEO", 1)), listOf(PartLine("FILTRO", 1)), opened)
	private val saga = WorkOrderSaga.start(workOrder.id, "corr", opened)

	@BeforeEach
	fun attachAppender() {
		appender.start()
		logger.addAppender(appender)
	}

	@AfterEach
	fun detachAppender() {
		logger.detachAppender(appender)
		if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization()
	}

	private fun lines() = appender.list.map { it.message }

	private fun keys(event: ILoggingEvent): Map<String, Any?> = event.keyValuePairs.orEmpty().associate { it.key to it.value }

	@Test
	fun `lifecycle events follow the saga status before and after the change`() {
		assertEquals(listOf("saga.started"), SagaLifecycle.lifecycleEvents(null, SagaStatus.RUNNING))
		assertEquals(listOf("saga.step.completed"), SagaLifecycle.lifecycleEvents(SagaStatus.RUNNING, SagaStatus.RUNNING))
		assertEquals(listOf("saga.step.completed", "saga.completed"), SagaLifecycle.lifecycleEvents(SagaStatus.RUNNING, SagaStatus.COMPLETED))
		assertEquals(listOf("saga.compensation.started"), SagaLifecycle.lifecycleEvents(SagaStatus.RUNNING, SagaStatus.COMPENSATING))
		assertEquals(
			listOf("saga.compensation.started", "saga.compensated"),
			SagaLifecycle.lifecycleEvents(SagaStatus.RUNNING, SagaStatus.COMPENSATED),
		)
		assertEquals(listOf("saga.compensation.confirmed"), SagaLifecycle.lifecycleEvents(SagaStatus.COMPENSATING, SagaStatus.COMPENSATING))
		assertEquals(
			listOf("saga.compensation.confirmed", "saga.compensated"),
			SagaLifecycle.lifecycleEvents(SagaStatus.COMPENSATING, SagaStatus.COMPENSATED),
		)
	}

	@Test
	fun `start is one line with the fixed keys and no duration`() {
		lifecycle.record(null, workOrder, saga, "WorkOrderOpened", opened)

		assertEquals(listOf("saga.started"), lines())
		assertEquals(
			mapOf(
				"sagaId" to saga.id,
				"step" to "QUOTE_GENERATION",
				"fromStatus" to null,
				"toStatus" to "RECEIVED",
				"trigger" to "WorkOrderOpened",
				"sagaStatus" to "RUNNING",
			),
			keys(appender.list.single()),
		)
	}

	@Test
	fun `step of the change is the step before it and the end carries the duration`() {
		val before = SagaSnapshot.of(workOrder, saga)
		workOrder.registerQuote(1, 1, 2, opened)
		saga.follow(workOrder.status, opened)
		lifecycle.record(before, workOrder, saga, "QuoteGenerated", opened)

		val cancelledBefore = SagaSnapshot.of(workOrder, saga)
		workOrder.cancel(CancellationCause.QUOTE_REJECTED, opened)
		saga.startCompensation(CancellationCause.QUOTE_REJECTED, "caro", opened)
		lifecycle.record(cancelledBefore, workOrder, saga, "QuoteRejected", opened.plusMillis(1_500))

		assertEquals(listOf("saga.step.completed", "saga.compensation.started", "saga.compensated"), lines())
		val step = keys(appender.list[0])
		assertEquals(SagaStep.QUOTE_GENERATION.name, step["step"])
		assertEquals(WorkOrderStatus.RECEIVED.name, step["fromStatus"])
		assertEquals(WorkOrderStatus.PENDING_APPROVAL.name, step["toStatus"])
		val compensated = keys(appender.list[2])
		assertEquals(SagaStep.CUSTOMER_APPROVAL.name, compensated["step"])
		assertEquals("QuoteRejected", compensated["trigger"])
		assertEquals("COMPENSATED", compensated["sagaStatus"])
		assertEquals(1_500L, compensated["durationMs"])
		assertEquals(null, keys(appender.list[1])["durationMs"])
	}

	@Test
	fun `sagaId already in the MDC is not repeated in the line`() {
		MDC.putCloseable("sagaId", saga.id.toString()).use {
			lifecycle.record(null, workOrder, saga, "WorkOrderOpened", opened)
		}

		val line = appender.list.single()
		assertEquals(null, keys(line)["sagaId"])
		assertEquals(saga.id.toString(), line.mdcPropertyMap["sagaId"])
		assertEquals("RECEIVED", keys(line)["toStatus"])
	}

	@Test
	fun `inside a transaction nothing is emitted before the commit`() {
		TransactionSynchronizationManager.initSynchronization()
		lifecycle.record(null, workOrder, saga, "WorkOrderOpened", opened)

		assertTrue(lines().isEmpty())
		assertTrue(tracing.events.isEmpty())

		TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
		assertEquals(listOf("saga.started"), lines())
		assertEquals(1, tracing.events.size)
	}

	@Test
	fun `failure of the agent is logged and does not propagate`() {
		val failing = mockk<Tracing>()
		every { failing.recordEvent(any(), any()) } throws IllegalStateException("agent")

		SagaLifecycle(failing).record(null, workOrder, saga, "WorkOrderOpened", opened)

		assertEquals(listOf("saga.started", "saga.telemetry.failed"), lines())
	}
}
