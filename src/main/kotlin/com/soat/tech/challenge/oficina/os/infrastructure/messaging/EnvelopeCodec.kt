package com.soat.tech.challenge.oficina.os.infrastructure.messaging

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.soat.tech.challenge.oficina.os.application.messaging.IncomingMessage
import com.soat.tech.challenge.oficina.os.application.messaging.MessageType
import com.soat.tech.challenge.oficina.os.application.messaging.OutboundCommand
import org.springframework.amqp.AmqpRejectAndDontRequeueException
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.core.MessageProperties
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Message that can never be processed (invalid JSON, type outside the contract, bad payload): DLQ, no retry. */
class PoisonMessageException(message: String) : AmqpRejectAndDontRequeueException(message)

/** Wire format of the envelope shared by the three services. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EnvelopeJson(
	val messageId: String? = null,
	val sagaId: String? = null,
	val type: String? = null,
	val occurredAt: String? = null,
	val correlationId: String? = null,
	val payload: JsonNode? = null,
)

/**
 * Serializes the envelope explicitly (no __TypeId__ header): JSON UTF-8 body, persistent delivery,
 * messageId and type repeated in the AMQP properties, correlation id in the x-correlation-id header.
 */
@Component
class EnvelopeCodec(private val mapper: JsonMapper) {

	fun encode(command: OutboundCommand, messageId: UUID, occurredAt: Instant): Message {
		val envelope = EnvelopeJson(
			messageId = messageId.toString(),
			sagaId = command.sagaId.toString(),
			type = command.type.typeName,
			occurredAt = occurredAt.truncatedTo(ChronoUnit.MILLIS).toString(),
			correlationId = command.correlationId,
			payload = mapper.valueToTree(command.payload),
		)
		val properties = MessageProperties().apply {
			contentType = MessageProperties.CONTENT_TYPE_JSON
			contentEncoding = Charsets.UTF_8.name()
			deliveryMode = MessageDeliveryMode.PERSISTENT
			this.messageId = messageId.toString()
			type = command.type.typeName
			setHeader(CORRELATION_ID_HEADER, command.correlationId)
		}
		return Message(mapper.writeValueAsBytes(envelope), properties)
	}

	fun decode(message: Message): IncomingMessage {
		val envelope = try {
			mapper.readValue(message.body, EnvelopeJson::class.java)
		} catch (e: JacksonException) {
			throw PoisonMessageException("Invalid JSON envelope")
		}
		val type = envelope.type?.let(MessageType::fromTypeName)
			?: throw PoisonMessageException("Type outside the contract")
		val messageId = uuid(envelope.messageId, "messageId")
		val sagaId = uuid(envelope.sagaId, "sagaId")
		val occurredAt = try {
			Instant.parse(envelope.occurredAt ?: throw PoisonMessageException("Missing occurredAt"))
		} catch (e: DateTimeParseException) {
			throw PoisonMessageException("Invalid occurredAt")
		}
		val payloadNode = envelope.payload?.takeIf { it.isObject } ?: throw PoisonMessageException("Missing payload")
		val payload = try {
			mapper.treeToValue(payloadNode, type.payloadType.java)
		} catch (e: JacksonException) {
			throw PoisonMessageException("Invalid payload for ${type.typeName}")
		}
		val workOrderId = payloadNode.get("workOrderId")?.takeIf { it.isValueNode }?.asString()
		if (workOrderId != null && workOrderId != sagaId.toString()) {
			throw PoisonMessageException("payload.workOrderId differs from sagaId")
		}
		return IncomingMessage(
			messageId = messageId,
			sagaId = sagaId,
			type = type,
			occurredAt = occurredAt,
			// Observability metadata only: a missing value must not block the saga.
			correlationId = envelope.correlationId?.takeIf { it.isNotBlank() } ?: messageId.toString(),
			payload = payload,
		)
	}

	private fun uuid(value: String?, field: String): UUID = try {
		UUID.fromString(value ?: throw PoisonMessageException("Missing $field"))
	} catch (e: IllegalArgumentException) {
		throw PoisonMessageException("Invalid $field")
	}

	companion object {
		const val CORRELATION_ID_HEADER = "x-correlation-id"
	}
}
