package com.soat.tech.challenge.oficina.os.infrastructure.messaging

import com.soat.tech.challenge.oficina.os.application.saga.SagaOrchestrator
import com.soat.tech.challenge.oficina.os.infrastructure.web.CorrelationIdFilter
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component

/**
 * Consumer of os.events. Decoding errors are poison (straight to the DLQ); any other failure is retried
 * by the listener container and then rejected without requeue, which also lands in the DLQ.
 */
@Component
class OsEventsListener(
	private val codec: EnvelopeCodec,
	private val orchestrator: SagaOrchestrator,
) {

	@RabbitListener(id = LISTENER_ID, queues = [Topology.OS_EVENTS])
	fun onMessage(message: Message) {
		val event = try {
			codec.decode(message)
		} catch (e: PoisonMessageException) {
			log.atWarn()
				.addKeyValue("messageId", message.messageProperties.messageId)
				.addKeyValue("type", message.messageProperties.type)
				.addKeyValue("detail", e.message)
				.log("message.rejected.poison")
			throw e
		}
		MDC.put(CorrelationIdFilter.REQUEST_ID_KEY, event.correlationId)
		try {
			log.atInfo()
				.addKeyValue("type", event.type.typeName)
				.addKeyValue("sagaId", event.sagaId)
				.addKeyValue("messageId", event.messageId)
				.log("message.consumed")
			orchestrator.handle(event)
		} finally {
			MDC.remove(CorrelationIdFilter.REQUEST_ID_KEY)
		}
	}

	companion object {
		const val LISTENER_ID = "os-events"
		private val log = LoggerFactory.getLogger(OsEventsListener::class.java)
	}
}
