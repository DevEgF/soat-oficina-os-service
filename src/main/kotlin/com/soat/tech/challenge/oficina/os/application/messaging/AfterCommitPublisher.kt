package com.soat.tech.challenge.oficina.os.application.messaging

import com.soat.tech.challenge.oficina.os.application.observability.addKeyValueUnlessInMdc
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Publishes commands only after the current transaction commits (emitAfterCommit of the monolith):
 * a rollback never leaks a command, and the state the next participant reacts to is already durable.
 */
@Component
class AfterCommitPublisher(private val publisher: CommandPublisher) {

	fun publishAfterCommit(command: OutboundCommand) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
				override fun afterCommit() = publishNow(command)
			})
		} else {
			publishNow(command)
		}
	}

	private fun publishNow(command: OutboundCommand) {
		runCatching { publisher.publish(command) }.onFailure {
			// The state is committed; without an outbox the command is lost and the saga stalls at this step.
			log.atError()
				.addKeyValue("type", command.type.typeName)
				.addKeyValueUnlessInMdc("sagaId", command.sagaId)
				.addKeyValue("exceptionClass", it.javaClass.name)
				.log("message.publish.failed")
		}
	}

	private companion object {
		val log = LoggerFactory.getLogger(AfterCommitPublisher::class.java)
	}
}
