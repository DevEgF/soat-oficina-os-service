package com.soat.tech.challenge.oficina.os.application.saga

import com.soat.tech.challenge.oficina.os.domain.port.WorkOrderRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApprovalTimeoutServiceTest {

	private val now = Instant.parse("2026-11-12T15:00:00Z")
	private val deadline = Duration.ofMinutes(30)
	private val workOrders = mockk<WorkOrderRepository>()
	private val orchestrator = mockk<SagaOrchestrator>()
	private val service = ApprovalTimeoutService(
		workOrders = workOrders,
		orchestrator = orchestrator,
		properties = SagaProperties(approvalTimeout = deadline),
		clock = Clock.fixed(now, ZoneOffset.UTC),
	)

	@Test
	fun `cancels every overdue work order and counts only the ones really cancelled`() {
		val ids = List(3) { UUID.randomUUID() }
		every { workOrders.findAwaitingApprovalSince(now.minus(deadline), any()) } returns ids
		every { orchestrator.expireApproval(ids[0], deadline) } returns true
		every { orchestrator.expireApproval(ids[1], deadline) } returns false
		every { orchestrator.expireApproval(ids[2], deadline) } returns true

		assertEquals(2, service.cancelOverdue())
	}

	@Test
	fun `a failure in one work order does not stop the others`() {
		val ids = List(3) { UUID.randomUUID() }
		every { workOrders.findAwaitingApprovalSince(any(), any()) } returns ids
		every { orchestrator.expireApproval(ids[0], deadline) } throws IllegalStateException("lock timeout")
		every { orchestrator.expireApproval(ids[1], deadline) } returns true
		every { orchestrator.expireApproval(ids[2], deadline) } returns true

		assertEquals(2, service.cancelOverdue())
		verify(exactly = 1) { orchestrator.expireApproval(ids[2], deadline) }
	}

	@Test
	fun `nothing overdue means nothing cancelled`() {
		every { workOrders.findAwaitingApprovalSince(any(), any()) } returns emptyList()

		assertEquals(0, service.cancelOverdue())
		verify(exactly = 0) { orchestrator.expireApproval(any(), any()) }
	}

	@Test
	fun `saga properties default to one day and thirty seconds and refuse non positive durations`() {
		val defaults = SagaProperties()
		assertEquals(Duration.ofHours(24), defaults.approvalTimeout)
		assertEquals(Duration.ofSeconds(30), defaults.approvalCheckInterval)
		assertFailsWith<IllegalArgumentException> { SagaProperties(approvalTimeout = Duration.ZERO) }
		assertFailsWith<IllegalArgumentException> { SagaProperties(approvalCheckInterval = Duration.ofSeconds(-1)) }
	}
}
