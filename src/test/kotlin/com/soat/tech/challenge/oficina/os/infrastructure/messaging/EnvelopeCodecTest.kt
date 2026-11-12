package com.soat.tech.challenge.oficina.os.infrastructure.messaging

import com.soat.tech.challenge.oficina.os.application.messaging.CustomerPayload
import com.soat.tech.challenge.oficina.os.application.messaging.ExecutionFinishedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.GenerateQuotePayload
import com.soat.tech.challenge.oficina.os.application.messaging.ItemPayload
import com.soat.tech.challenge.oficina.os.application.messaging.MessageType
import com.soat.tech.challenge.oficina.os.application.messaging.OutboundCommand
import com.soat.tech.challenge.oficina.os.application.messaging.PaymentApprovedPayload
import com.soat.tech.challenge.oficina.os.application.messaging.QuoteGeneratedPayload
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.core.MessageProperties
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvelopeCodecTest {

	private val mapper = jacksonMapperBuilder().build()
	private val codec = EnvelopeCodec(mapper)
	private val sagaId = UUID.fromString("7d3c0b52-4b4f-4f55-8a5e-0d6f0b9e2a11")
	private val messageId = UUID.fromString("0b0e6f0e-8a57-4a5e-9d0b-3a1f0c1d2e3f")

	private fun message(json: String) = Message(json.toByteArray(), MessageProperties())

	private fun envelope(type: String, payload: String, extra: String = "") = """
		{"messageId":"$messageId","sagaId":"$sagaId","type":"$type","occurredAt":"2026-11-12T15:04:05.123Z",
		 "correlationId":"corr-1","payload":$payload$extra}
	""".trimIndent()

	@Test
	fun `encode writes the spec envelope with amqp properties and no type id header`() {
		val command = OutboundCommand(
			sagaId = sagaId,
			type = MessageType.GENERATE_QUOTE,
			correlationId = "corr-1",
			payload = GenerateQuotePayload(
				sagaId, "track-1", CustomerPayload("Maria", null), listOf(ItemPayload("TROCA_OLEO", 1)), emptyList(),
			),
		)

		val message = codec.encode(command, messageId, Instant.parse("2026-11-12T15:04:05.123456Z"))

		val props = message.messageProperties
		assertEquals(MessageProperties.CONTENT_TYPE_JSON, props.contentType)
		assertEquals("UTF-8", props.contentEncoding)
		assertEquals(MessageDeliveryMode.PERSISTENT, props.deliveryMode)
		assertEquals(messageId.toString(), props.messageId)
		assertEquals("GenerateQuote", props.type)
		assertEquals("corr-1", props.getHeader<String>("x-correlation-id"))
		assertFalse(props.headers.containsKey("__TypeId__"))
		val json = mapper.readTree(message.body)
		assertEquals(messageId.toString(), json["messageId"].asString())
		assertEquals(sagaId.toString(), json["sagaId"].asString())
		assertEquals("GenerateQuote", json["type"].asString())
		assertEquals("2026-11-12T15:04:05.123Z", json["occurredAt"].asString())
		assertEquals("corr-1", json["correlationId"].asString())
		val payload = json["payload"]
		assertEquals(sagaId.toString(), payload["workOrderId"].asString())
		assertEquals("track-1", payload["trackingCode"].asString())
		assertEquals("Maria", payload["customer"]["name"].asString())
		assertEquals("TROCA_OLEO", payload["services"][0]["code"].asString())
		assertEquals(1, payload["services"][0]["quantity"].asInt())
		assertTrue(payload["parts"].isArray)
	}

	@Test
	fun `decode reads the envelope and types the payload ignoring unknown fields`() {
		val quoteId = UUID.randomUUID()
		val json = envelope(
			"QuoteGenerated",
			"""{"workOrderId":"$sagaId","quoteId":"$quoteId","servicesTotalCents":12000,"partsTotalCents":9000,"totalCents":21000,"currency":"BRL"}""",
			""","schemaVersion":2""",
		)

		val event = codec.decode(message(json))

		assertEquals(messageId, event.messageId)
		assertEquals(sagaId, event.sagaId)
		assertEquals(MessageType.QUOTE_GENERATED, event.type)
		assertEquals(Instant.parse("2026-11-12T15:04:05.123Z"), event.occurredAt)
		assertEquals("corr-1", event.correlationId)
		assertEquals(QuoteGeneratedPayload(sagaId, quoteId, 12000, 9000, 21000), event.payload)
	}

	@Test
	fun `decode accepts instants and optional fields of the payload`() {
		val payment = codec.decode(
			message(
				envelope(
					"PaymentApproved",
					"""{"workOrderId":"$sagaId","quoteId":"$sagaId","paymentId":"$sagaId","amountCents":5,"provider":"SIMULATED"}""",
				),
			),
		).payload as PaymentApprovedPayload
		assertEquals("SIMULATED", payment.provider)

		val finished = codec.decode(
			message(envelope("ExecutionFinished", """{"workOrderId":"$sagaId","finishedAt":"2026-11-12T16:00:00Z"}""")),
		).payload as ExecutionFinishedPayload
		assertEquals(Instant.parse("2026-11-12T16:00:00Z"), finished.finishedAt)
	}

	@Test
	fun `missing correlation id falls back to the message id`() {
		val json = """{"messageId":"$messageId","sagaId":"$sagaId","type":"ExecutionFinished","occurredAt":"2026-11-12T15:04:05Z",
			"payload":{"workOrderId":"$sagaId","finishedAt":"2026-11-12T16:00:00Z"}}"""

		assertEquals(messageId.toString(), codec.decode(message(json)).correlationId)
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"not json",
			"[]",
			"""{"messageId":"x"}""",
		],
	)
	fun `invalid json is poison`(body: String) {
		assertFailsWith<PoisonMessageException> { codec.decode(message(body)) }
	}

	@Test
	fun `type outside the contract is poison`() {
		assertFailsWith<PoisonMessageException> { codec.decode(message(envelope("Bogus", "{}"))) }
	}

	@Test
	fun `invalid envelope fields are poison`() {
		val payload = """{"workOrderId":"$sagaId","finishedAt":"2026-11-12T16:00:00Z"}"""
		val valid = envelope("ExecutionFinished", payload)
		listOf(
			valid.replace("\"$messageId\"", "\"not-a-uuid\""),
			valid.replace("\"messageId\":\"$messageId\",", ""),
			valid.replace("\"sagaId\":\"$sagaId\"", "\"sagaId\":\"123\""),
			valid.replace("2026-11-12T15:04:05.123Z", "yesterday"),
			valid.replace("\"occurredAt\":\"2026-11-12T15:04:05.123Z\",", ""),
			envelope("ExecutionFinished", "null"),
			envelope("ExecutionFinished", "\"text\""),
		).forEach { body -> assertFailsWith<PoisonMessageException>(body) { codec.decode(message(body)) } }
	}

	@Test
	fun `payload missing required fields or for another work order is poison`() {
		assertFailsWith<PoisonMessageException> {
			codec.decode(message(envelope("QuoteGenerated", """{"workOrderId":"$sagaId","quoteId":"$sagaId"}""")))
		}
		assertFailsWith<PoisonMessageException> {
			codec.decode(message(envelope("ExecutionFinished", """{"workOrderId":"${UUID.randomUUID()}","finishedAt":"2026-11-12T16:00:00Z"}""")))
		}
	}
}
