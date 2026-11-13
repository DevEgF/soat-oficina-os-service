package com.soat.tech.challenge.oficina.os.infrastructure.messaging

import com.newrelic.api.agent.Trace
import com.soat.tech.challenge.oficina.os.application.messaging.IncomingMessage
import com.soat.tech.challenge.oficina.os.application.observability.Tracing
import com.soat.tech.challenge.oficina.os.application.observability.addKeyValueUnlessInMdc
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
 *
 * Each delivery (and each retry) is one transaction of the APM agent that first accepts the trace headers
 * of the message, so the commands published while it is processed continue the same distributed trace.
 * While it runs the MDC carries requestId (correlation id), sagaId, messageType and messageId.
 */
@Component
class OsEventsListener(
	private val codec: EnvelopeCodec,
	private val orchestrator: SagaOrchestrator,
	private val tracing: Tracing,
) {

	@Trace(dispatcher = true)
	@RabbitListener(id = LISTENER_ID, queues = [Topology.OS_EVENTS])
	fun onMessage(message: Message) {
		val properties = message.messageProperties
		tracing.acceptMessageTraceHeaders(properties.headers)
		val queue = properties.consumerQueue ?: Topology.OS_EVENTS
		try {
			properties.messageId?.let { MDC.put(MESSAGE_ID_KEY, it) }
			properties.type?.let { MDC.put(MESSAGE_TYPE_KEY, it) }
			val event = decode(message, queue)
			tracing.nameTransaction(TRANSACTION_CATEGORY, "$queue/${event.type.typeName}")
			tracing.addAttributes(
				mapOf(
					"sagaId" to event.sagaId.toString(),
					"messageType" to event.type.typeName,
					"messageId" to event.messageId.toString(),
					"correlationId" to event.correlationId,
				),
			)
			MDC.put(CorrelationIdFilter.REQUEST_ID_KEY, event.correlationId)
			MDC.put(SAGA_ID_KEY, event.sagaId.toString())
			MDC.put(MESSAGE_TYPE_KEY, event.type.typeName)
			MDC.put(MESSAGE_ID_KEY, event.messageId.toString())
			// sagaId and messageId are already in the MDC.
			log.atInfo().addKeyValue("type", event.type.typeName).log("message.consumed")
			orchestrator.handle(event)
		} finally {
			MDC_KEYS.forEach(MDC::remove)
		}
	}

	private fun decode(message: Message, queue: String): IncomingMessage = try {
		codec.decode(message)
	} catch (e: PoisonMessageException) {
		val type = message.messageProperties.type ?: UNKNOWN_TYPE
		tracing.nameTransaction(TRANSACTION_CATEGORY, "$queue/$type")
		log.atWarn()
			.addKeyValueUnlessInMdc("messageId", message.messageProperties.messageId)
			.addKeyValue("type", message.messageProperties.type)
			.addKeyValue("detail", e.message)
			.log("message.rejected.poison")
		throw e
	}

	companion object {
		const val LISTENER_ID = "os-events"
		const val TRANSACTION_CATEGORY = "Message"
		const val SAGA_ID_KEY = "sagaId"
		const val MESSAGE_TYPE_KEY = "messageType"
		const val MESSAGE_ID_KEY = "messageId"
		private const val UNKNOWN_TYPE = "unknown"
		private val MDC_KEYS = listOf(CorrelationIdFilter.REQUEST_ID_KEY, SAGA_ID_KEY, MESSAGE_TYPE_KEY, MESSAGE_ID_KEY)
		private val log = LoggerFactory.getLogger(OsEventsListener::class.java)
	}
}
