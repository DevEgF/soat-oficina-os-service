package com.soat.tech.challenge.oficina.os.domain.model

import com.soat.tech.challenge.oficina.os.domain.exception.InvalidStatusTransitionException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Work order (WorkOrder)")
class WorkOrderTest {

	private val t0 = Instant.parse("2026-11-12T10:00:00Z")

	private fun newWorkOrder() = WorkOrder.open(
		customerId = UUID.randomUUID(),
		vehicleId = UUID.randomUUID(),
		serviceLines = listOf(ServiceLine("TROCA_OLEO", 1)),
		partLines = listOf(PartLine("FILTRO_OLEO", 2)),
		now = t0,
	)

	@Nested
	@DisplayName("Given a new work order")
	inner class GivenNewWorkOrder {

		@Test
		fun `when opened then it is RECEIVED without quote totals`() {
			val wo = newWorkOrder()
			assertEquals(WorkOrderStatus.RECEIVED, wo.status)
			assertNull(wo.totalCents)
			assertEquals(t0, wo.createdAt)
			assertEquals(t0, wo.updatedAt)
		}

		@Test
		fun `when the saga happy path runs then status reaches DELIVERED`() {
			val wo = newWorkOrder()
			wo.registerQuote(12000, 9000, 21000, t0.plusSeconds(1))
			assertEquals(WorkOrderStatus.PENDING_APPROVAL, wo.status)
			assertEquals(21000, wo.totalCents)
			assertEquals(12000, wo.servicesTotalCents)
			assertEquals(9000, wo.partsTotalCents)
			wo.approveQuote(t0.plusSeconds(2))
			assertEquals(WorkOrderStatus.AWAITING_PAYMENT, wo.status)
			wo.confirmPayment(t0.plusSeconds(3))
			assertEquals(WorkOrderStatus.QUEUED, wo.status)
			wo.startDiagnosis(t0.plusSeconds(4))
			assertEquals(WorkOrderStatus.IN_DIAGNOSIS, wo.status)
			wo.startRepair(t0.plusSeconds(5))
			assertEquals(WorkOrderStatus.IN_EXECUTION, wo.status)
			wo.finish(t0.plusSeconds(6))
			assertEquals(WorkOrderStatus.FINALIZED, wo.status)
			wo.registerDelivery(t0.plusSeconds(7))
			assertEquals(WorkOrderStatus.DELIVERED, wo.status)
			assertEquals(t0.plusSeconds(1), wo.quotedAt)
			assertEquals(t0.plusSeconds(2), wo.approvedAt)
			assertEquals(t0.plusSeconds(3), wo.paidAt)
			assertEquals(t0.plusSeconds(4), wo.diagnosisStartedAt)
			assertEquals(t0.plusSeconds(5), wo.repairStartedAt)
			assertEquals(t0.plusSeconds(6), wo.finishedAt)
			assertEquals(t0.plusSeconds(7), wo.deliveredAt)
			assertEquals(t0.plusSeconds(7), wo.updatedAt)
		}

		@Test
		fun `when a step is skipped then the transition is refused and the status kept`() {
			val wo = newWorkOrder()
			val error = assertFailsWith<InvalidStatusTransitionException> { wo.approveQuote(t0) }
			assertEquals("RECEIVED", error.currentStatus)
			assertEquals("approveQuote", error.action)
			assertFailsWith<InvalidStatusTransitionException> { wo.confirmPayment(t0) }
			assertFailsWith<InvalidStatusTransitionException> { wo.startDiagnosis(t0) }
			assertFailsWith<InvalidStatusTransitionException> { wo.startRepair(t0) }
			assertFailsWith<InvalidStatusTransitionException> { wo.finish(t0) }
			assertFailsWith<InvalidStatusTransitionException> { wo.registerDelivery(t0) }
			assertEquals(WorkOrderStatus.RECEIVED, wo.status)
		}

		@Test
		fun `when the quote arrives twice then the second is refused`() {
			val wo = newWorkOrder()
			wo.registerQuote(1, 2, 3, t0)
			assertFailsWith<InvalidStatusTransitionException> { wo.registerQuote(1, 2, 3, t0) }
		}

		@Test
		fun `when quote totals are negative then they are refused`() {
			assertFailsWith<IllegalArgumentException> { newWorkOrder().registerQuote(-1, 0, 0, t0) }
		}
	}

	@Nested
	@DisplayName("Given the opening data")
	inner class GivenOpeningData {

		@Test
		fun `when there is no item then opening is refused`() {
			assertFailsWith<IllegalArgumentException> {
				WorkOrder.open(UUID.randomUUID(), UUID.randomUUID(), emptyList(), emptyList(), t0)
			}
		}

		@Test
		fun `when only parts are requested then the work order opens`() {
			val wo = WorkOrder.open(UUID.randomUUID(), UUID.randomUUID(), emptyList(), listOf(PartLine("BATERIA_60AH", 1)), t0)
			assertEquals(1, wo.partLines.size)
		}

		@Test
		fun `when a line has invalid code or quantity then it is refused`() {
			assertFailsWith<IllegalArgumentException> { ServiceLine(" ", 1) }
			assertFailsWith<IllegalArgumentException> { ServiceLine("TROCA_OLEO", 0) }
			assertFailsWith<IllegalArgumentException> { PartLine("", 1) }
			assertFailsWith<IllegalArgumentException> { PartLine("FILTRO_OLEO", -1) }
		}
	}

	@Nested
	@DisplayName("Given a cancellation (compensation)")
	inner class GivenCancellation {

		/** Work order moved to [status] through the regular transitions. */
		private fun workOrderIn(status: WorkOrderStatus): WorkOrder = newWorkOrder().apply {
			val path = listOf<WorkOrder.() -> Unit>(
				{ registerQuote(1, 1, 2, t0) }, { approveQuote(t0) }, { confirmPayment(t0) },
				{ startDiagnosis(t0) }, { startRepair(t0) }, { finish(t0) }, { registerDelivery(t0) },
			)
			val order = WorkOrderStatus.entries.takeWhile { it != WorkOrderStatus.CANCELLED }
			path.take(order.indexOf(status)).forEach { it() }
			check(this.status == status)
		}

		@ParameterizedTest
		@EnumSource(CancellationCause::class)
		fun `when the cause applies to the status then it is CANCELLED with the cause as reason`(cause: CancellationCause) {
			cause.allowedFrom.forEach { from ->
				val wo = workOrderIn(from)
				wo.cancel(cause, t0.plusSeconds(1))
				assertEquals(WorkOrderStatus.CANCELLED, wo.status)
				assertEquals(t0.plusSeconds(1), wo.cancelledAt)
				assertEquals(t0.plusSeconds(1), wo.updatedAt)
				assertEquals(cause.reason, wo.cancellationReason)
			}
		}

		@ParameterizedTest
		@EnumSource(CancellationCause::class)
		fun `when the cause does not apply to the status then cancel is refused`(cause: CancellationCause) {
			val others = WorkOrderStatus.entries.filter { it !in cause.allowedFrom && it != WorkOrderStatus.CANCELLED }
			others.forEach { from ->
				val wo = workOrderIn(from)
				assertFailsWith<InvalidStatusTransitionException> { wo.cancel(cause, t0) }
				assertEquals(from, wo.status)
				assertNull(wo.cancellationReason)
			}
			val cancelled = workOrderIn(cause.allowedFrom.first()).apply { cancel(cause, t0) }
			assertFailsWith<InvalidStatusTransitionException> { cancelled.cancel(cause, t0) }
		}

		@Test
		fun `cancellation reasons and compensations follow the contract`() {
			assertEquals(
				listOf("QuoteGenerationFailed", "QuoteRejected", "ApprovalTimeout", "PaymentRejected", "ExecutionFailed"),
				CancellationCause.entries.map { it.reason },
			)
			assertEquals(listOf(Compensation.CANCEL_QUOTE), CancellationCause.APPROVAL_TIMEOUT.compensations)
			assertEquals(listOf(Compensation.CANCEL_QUOTE), CancellationCause.PAYMENT_REJECTED.compensations)
			assertEquals(
				listOf(Compensation.REFUND_PAYMENT, Compensation.CANCEL_QUOTE),
				CancellationCause.EXECUTION_FAILED.compensations,
			)
			assertTrue(CancellationCause.QUOTE_GENERATION_FAILED.compensations.isEmpty())
			assertTrue(CancellationCause.QUOTE_REJECTED.compensations.isEmpty())
		}
	}

	@Nested
	@DisplayName("Given the approval deadline")
	inner class GivenApprovalDeadline {

		private val deadline = Duration.ofMinutes(30)

		@Test
		fun `when pending approval longer than the deadline then it is overdue`() {
			val wo = newWorkOrder().apply { registerQuote(1, 1, 2, t0) }
			assertFalse(wo.approvalOverdue(deadline, t0.plus(deadline)))
			assertTrue(wo.approvalOverdue(deadline, t0.plus(deadline).plusMillis(1)))
		}

		@Test
		fun `when not pending approval then it is never overdue`() {
			val later = t0.plus(Duration.ofDays(2))
			assertFalse(newWorkOrder().approvalOverdue(deadline, later))
			val approved = newWorkOrder().apply { registerQuote(1, 1, 2, t0); approveQuote(t0) }
			assertFalse(approved.approvalOverdue(deadline, later))
		}
	}

	@ParameterizedTest
	@EnumSource(WorkOrderStatus::class)
	fun `every status has a portuguese label`(status: WorkOrderStatus) {
		assertTrue(status.label.isNotBlank())
	}

	@Test
	fun `labels are the Portuguese names shown by the API`() {
		assertEquals(
			listOf(
				"Recebida", "Aguardando Aprovação", "Aguardando Pagamento", "Na Fila", "Diagnóstico",
				"Execução", "Finalizada", "Entregue", "Cancelada",
			),
			WorkOrderStatus.entries.map { it.label },
		)
		assertTrue(WorkOrderStatus.DELIVERED.terminal)
		assertTrue(WorkOrderStatus.CANCELLED.terminal)
		assertFalse(WorkOrderStatus.FINALIZED.terminal)
	}
}
