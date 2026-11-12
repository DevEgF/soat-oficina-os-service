package com.soat.tech.challenge.oficina.os.infrastructure.messaging

import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.function.Predicate

/**
 * Listener retry comes from spring.rabbitmq.listener.simple.retry.*; this only keeps poison messages
 * out of it, so they reach the DLQ on the first failure.
 */
@Configuration
class ListenerRetryConfiguration {

	@Bean
	fun poisonMessagesAreNotRetried() = RabbitListenerRetrySettingsCustomizer { settings ->
		settings.exceptionPredicate = Predicate(::isRetryable)
	}

	companion object {
		fun isRetryable(error: Throwable): Boolean =
			generateSequence(error) { it.cause }.none { it is PoisonMessageException }
	}
}
