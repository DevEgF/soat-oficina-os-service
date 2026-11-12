package com.soat.tech.challenge.oficina.os.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.soat.tech.challenge.oficina.os.support.TestJwt
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.rabbitmq.RabbitMQContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Full context against real PostgreSQL and RabbitMQ containers, shared by every integration test class
 * (one Spring context, containers started once per JVM).
 * Properties are inlined so they win over APP_* variables exported in the developer shell; the staff
 * passwords equal to the user names are accepted only because the profile is "test".
 */
@SpringBootTest(
	webEnvironment = WebEnvironment.RANDOM_PORT,
	properties = [
		"app.environment=hml",
		"app.jwt.secret=${TestJwt.RAW_SECRET}",
		"app.security.admin.password=admin",
		"app.security.attendant.password=atendente",
		"app.security.technician.password=tecnico",
	],
)
@ActiveProfiles("test")
abstract class IntegrationTestSupport {

	@LocalServerPort
	protected var port: Int = 0

	protected val http: HttpClient = HttpClient.newHttpClient()
	protected val json = ObjectMapper()

	protected fun get(path: String, token: String? = null): HttpResponse<String> =
		send(HttpRequest.newBuilder(uri(path)).GET(), token)

	protected fun post(path: String, body: String?, token: String? = null): HttpResponse<String> {
		val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
		return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").POST(publisher), token)
	}

	protected fun body(response: HttpResponse<String>): JsonNode = json.readTree(response.body())

	private fun uri(path: String) = URI.create("http://localhost:$port$path")

	private fun send(request: HttpRequest.Builder, token: String?): HttpResponse<String> {
		token?.let { request.header("Authorization", "Bearer $it") }
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
	}

	companion object {
		@ServiceConnection
		@JvmStatic
		val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16.15-alpine").apply { start() }

		@ServiceConnection
		@JvmStatic
		val rabbit: RabbitMQContainer = RabbitMQContainer("rabbitmq:4.2.9-alpine").apply { start() }
	}
}
