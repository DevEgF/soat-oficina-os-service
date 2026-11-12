package com.soat.tech.challenge.oficina.os.infrastructure.messaging

import com.soat.tech.challenge.oficina.os.application.messaging.CommandPublisher
import com.soat.tech.challenge.oficina.os.application.messaging.OutboundCommand
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

@Component
class RabbitCommandPublisher(
	private val rabbitTemplate: RabbitTemplate,
	private val codec: EnvelopeCodec,
	private val clock: Clock,
) : CommandPublisher {

	override fun publish(command: OutboundCommand) {
		val messageId = UUID.randomUUID()
		val message = codec.encode(command, messageId, clock.instant())
		rabbitTemplate.send(Topology.EXCHANGE, command.type.routingKey, message)
		log.atInfo()
			.addKeyValue("type", command.type.typeName)
			.addKeyValue("sagaId", command.sagaId)
			.addKeyValue("messageId", messageId)
			.addKeyValue("routingKey", command.type.routingKey)
			.log("message.published")
	}

	private companion object {
		val log = LoggerFactory.getLogger(RabbitCommandPublisher::class.java)
	}
}
