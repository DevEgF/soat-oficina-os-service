package com.soat.tech.challenge.oficina.os.infrastructure

import com.newrelic.api.agent.Trace
import com.soat.tech.challenge.oficina.os.application.observability.Tracing
import com.soat.tech.challenge.oficina.os.application.saga.ApprovalTimeoutService
import com.soat.tech.challenge.oficina.os.application.saga.SagaProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Configuration
@EnableScheduling
@EnableConfigurationProperties(SagaProperties::class)
class SchedulingConfiguration

/**
 * Periodic check of the approval deadline, every app.saga.approval-check-interval (ISO-8601 duration).
 * Each run is one transaction of the APM agent (Job/ApprovalTimeoutCheck), so the CancelQuote commands
 * it publishes carry a trace.
 */
@Component
class ApprovalTimeoutScheduler(
	private val service: ApprovalTimeoutService,
	private val tracing: Tracing,
) {

	@Trace(dispatcher = true)
	@Scheduled(
		initialDelayString = "\${app.saga.approval-check-interval:PT30S}",
		fixedDelayString = "\${app.saga.approval-check-interval:PT30S}",
	)
	fun checkApprovalDeadline() {
		tracing.nameTransaction(TRANSACTION_CATEGORY, TRANSACTION_NAME)
		service.cancelOverdue()
	}

	companion object {
		const val TRANSACTION_CATEGORY = "Job"
		const val TRANSACTION_NAME = "ApprovalTimeoutCheck"
	}
}
