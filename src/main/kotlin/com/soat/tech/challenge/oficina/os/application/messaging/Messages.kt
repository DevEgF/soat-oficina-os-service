package com.soat.tech.challenge.oficina.os.application.messaging

import java.time.Instant
import java.util.UUID

/** Event received from the broker, already decoded from the envelope (payload typed by [MessageType.payloadType]). */
data class IncomingMessage(
	val messageId: UUID,
	val sagaId: UUID,
	val type: MessageType,
	val occurredAt: Instant,
	val correlationId: String,
	val payload: Any,
)

/** Command the orchestrator wants published once the transaction commits. */
data class OutboundCommand(
	val sagaId: UUID,
	val type: MessageType,
	val correlationId: String,
	val payload: Any,
) {
	init {
		require(type.kind == MessageKind.COMMAND) { "${type.typeName} is not a command" }
		require(type.payloadType.isInstance(payload)) { "Payload does not match ${type.typeName}" }
	}
}

/** Outbound port: publishes a command to the broker (envelope and routing are infrastructure). */
interface CommandPublisher {
	fun publish(command: OutboundCommand)
}
