package com.soat.tech.challenge.oficina.os.infrastructure

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

/** Periodic check of the approval deadline, every app.saga.approval-check-interval (ISO-8601 duration). */
@Component
class ApprovalTimeoutScheduler(private val service: ApprovalTimeoutService) {

	@Scheduled(
		initialDelayString = "\${app.saga.approval-check-interval:PT30S}",
		fixedDelayString = "\${app.saga.approval-check-interval:PT30S}",
	)
	fun checkApprovalDeadline() {
		service.cancelOverdue()
	}
}
