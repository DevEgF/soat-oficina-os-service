package com.soat.tech.challenge.oficina.os.infrastructure.messaging

import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Declarable
import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.core.TopicExchange
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Names of the shared RabbitMQ topology (identical in the three services). */
object Topology {
	const val EXCHANGE = "oficina"
	const val DEAD_LETTER_EXCHANGE = "oficina.dlx"
	const val OS_EVENTS = "os.events"
	const val BILLING_COMMANDS = "billing.commands"
	const val EXECUTION_COMMANDS = "execution.commands"

	/** Queue -> binding pattern on [EXCHANGE]. This service consumes os.events and publishes to the other two. */
	val queues: Map<String, String> = linkedMapOf(
		OS_EVENTS to "event.#",
		BILLING_COMMANDS to "command.billing.#",
		EXECUTION_COMMANDS to "command.execution.#",
	)

	fun deadLetterQueue(queue: String) = "$queue.dlq"
}

/**
 * Declared at startup by the auto-configured RabbitAdmin (idempotent). Arguments must match the other
 * services exactly, otherwise the broker refuses the declaration with PRECONDITION_FAILED.
 */
@Configuration
class MessagingTopology {

	@Bean
	fun oficinaTopology(): Declarables {
		val exchange = TopicExchange(Topology.EXCHANGE, true, false)
		val deadLetterExchange = DirectExchange(Topology.DEAD_LETTER_EXCHANGE, true, false)
		val declarables = mutableListOf<Declarable>(exchange, deadLetterExchange)
		Topology.queues.forEach { (name, pattern) ->
			val dlqName = Topology.deadLetterQueue(name)
			val queue = workQueue(name)
			val dlq = QueueBuilder.durable(dlqName).build()
			declarables += queue
			declarables += dlq
			declarables += BindingBuilder.bind(queue).to(exchange).with(pattern)
			declarables += BindingBuilder.bind(dlq).to(deadLetterExchange).with(dlqName)
		}
		return Declarables(declarables)
	}

	companion object {
		fun workQueue(name: String): Queue = QueueBuilder.durable(name)
			.deadLetterExchange(Topology.DEAD_LETTER_EXCHANGE)
			.deadLetterRoutingKey(Topology.deadLetterQueue(name))
			.build()
	}
}
