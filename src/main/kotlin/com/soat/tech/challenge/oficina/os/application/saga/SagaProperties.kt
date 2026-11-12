package com.soat.tech.challenge.oficina.os.application.saga

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Saga settings (app.saga.*).
 *
 * @property approvalTimeout how long a work order may wait in PENDING_APPROVAL before it is cancelled.
 * @property approvalCheckInterval pause between two runs of the deadline check (read by the scheduler).
 */
@ConfigurationProperties("app.saga")
data class SagaProperties(
	val approvalTimeout: Duration = Duration.ofHours(24),
	val approvalCheckInterval: Duration = Duration.ofSeconds(30),
) {
	init {
		require(approvalTimeout > Duration.ZERO) { "app.saga.approval-timeout must be positive" }
		require(approvalCheckInterval > Duration.ZERO) { "app.saga.approval-check-interval must be positive" }
	}
}
