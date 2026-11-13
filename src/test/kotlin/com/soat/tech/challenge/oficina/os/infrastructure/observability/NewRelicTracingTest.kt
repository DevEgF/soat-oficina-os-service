package com.soat.tech.challenge.oficina.os.infrastructure.observability

import com.newrelic.api.agent.HeaderType
import com.soat.tech.challenge.oficina.os.application.saga.ApprovalTimeoutService
import com.soat.tech.challenge.oficina.os.infrastructure.ApprovalTimeoutScheduler
import com.soat.tech.challenge.oficina.os.support.RecordingTracing
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NewRelicTracingTest {

	@Test
	fun `only text headers are handed to the agent as message headers`() {
		val headers = NewRelicTracing.traceHeaders(
			mapOf("traceparent" to "00-abc-def-01", "tracestate" to StringBuilder("nr=1"), "x-death" to listOf(1), "empty" to null),
		)

		assertEquals(HeaderType.MESSAGE, headers.headerType)
		assertEquals("00-abc-def-01", headers.getHeader("traceparent"))
		assertEquals("nr=1", headers.getHeader("tracestate"))
		assertNull(headers.getHeader("x-death"))
		assertEquals(setOf("traceparent", "tracestate"), headers.headerNames.toSet())
	}

	@Test
	fun `without the agent every call is a no-op`() {
		val tracing = NewRelicTracing()

		tracing.acceptMessageTraceHeaders(mapOf("traceparent" to "00-abc-def-01"))
		tracing.nameTransaction("Message", "os.events/QuoteApproved")
		tracing.addAttributes(mapOf("sagaId" to "s", "durationMs" to 1L, "retried" to false))
		tracing.recordEvent("SagaTransition", mapOf("sagaId" to "s"))
	}

	@Test
	fun `each run of the approval deadline check is a named job transaction`() {
		val service = mockk<ApprovalTimeoutService>()
		every { service.cancelOverdue() } returns 0
		val tracing = RecordingTracing()

		ApprovalTimeoutScheduler(service, tracing).checkApprovalDeadline()

		assertEquals(listOf("Job/ApprovalTimeoutCheck"), tracing.transactionNames)
		verify(exactly = 1) { service.cancelOverdue() }
	}
}
