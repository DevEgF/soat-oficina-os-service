package com.soat.tech.challenge.oficina.os.integration

import com.soat.tech.challenge.oficina.os.domain.model.Customer
import com.soat.tech.challenge.oficina.os.domain.model.TaxDocument
import com.soat.tech.challenge.oficina.os.domain.port.CustomerRepository
import com.soat.tech.challenge.oficina.os.support.TestJwt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/** GET /api/internal/customers/{taxId}: lookup used by the customer authentication service. */
class InternalCustomerIntegrationTest : IntegrationTestSupport() {

	@Autowired
	private lateinit var customers: CustomerRepository

	/** Token the authentication service signs for itself: subject is the service, scope INTERNAL, 60 s. */
	private fun internalToken() = TestJwt.token(
		subject = "soat-oficina-auth",
		scope = listOf("INTERNAL"),
		expiresAt = Instant.now().plusSeconds(60),
	)

	private fun customer(document: String): Customer =
		customers.save(Customer(UUID.randomUUID(), TaxDocument.parse(document), "Cliente Teste", "cliente@example.com"))

	@Test
	fun `internal token finds the customer by CPF and by CNPJ`() {
		val byCpf = customer(randomCpf())
		val byCnpj = customer(randomCnpj())

		listOf(byCpf, byCnpj).forEach { saved ->
			val response = get("/api/internal/customers/${saved.taxDocument.digits}", internalToken())

			assertEquals(200, response.statusCode(), response.body())
			val body = body(response)
			assertEquals(setOf("id", "status"), body.fieldNames().asSequence().toSet())
			assertEquals(saved.id.toString(), body["id"].asText())
			assertEquals("ACTIVE", body["status"].asText())
		}
	}

	@Test
	fun `unknown document is 404 and anything but a valid digits-only document is 400`() {
		assertEquals(404, get("/api/internal/customers/${randomCpf()}", internalToken()).statusCode())
		listOf("123", "11111111111", "52998224724", "529.982.247-25", "5299822472a").forEach { taxId ->
			val response = get("/api/internal/customers/$taxId", internalToken())
			assertEquals(400, response.statusCode(), taxId)
			assertTrue(body(response).has("requestId"))
		}
	}

	@Test
	fun `only the INTERNAL scope gets in`() {
		val path = "/api/internal/customers/${customer(randomCpf()).taxDocument.digits}"

		assertEquals(401, get(path).statusCode())
		assertEquals(401, get(path, TestJwt.token(subject = "soat-oficina-auth", scope = listOf("INTERNAL"), environment = "prod")).statusCode())
		assertEquals(403, get(path, TestJwt.token(subject = "atendente", scope = listOf("ATTENDANT"))).statusCode())
		assertEquals(403, get(path, TestJwt.token(subject = "admin", scope = listOf("ADMIN"))).statusCode())
		assertEquals(403, get(path, TestJwt.token()).statusCode(), "customer token")
		val login = post("/api/public/auth/login", """{"username":"atendente","password":"atendente"}""")
		assertEquals(403, get(path, body(login)["accessToken"].asText()).statusCode(), "staff login token")
	}

	@Test
	fun `route is in the OpenAPI document`() {
		val paths = body(get("/v3/api-docs"))["paths"]
		assertTrue(paths.has("/api/internal/customers/{taxId}"))
	}

	private fun randomCpf(): String = withCheckDigits((1..9).map { Random.nextInt(10) }) { size -> (size + 1 downTo 2).toList() }

	private fun randomCnpj(): String = withCheckDigits((1..12).map { Random.nextInt(10) }) { size ->
		if (size == 12) listOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2) else listOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
	}

	/** Appends the two mod-11 check digits; [weights] gives the weights for a base of the given size. */
	private fun withCheckDigits(random: List<Int>, weights: (Int) -> List<Int>): String {
		// A document with every digit equal is invalid even with the right check digits.
		val base = if (random.all { it == random[0] }) listOf((random[0] + 1) % 10) + random.drop(1) else random
		fun digit(numbers: List<Int>): Int {
			val r = numbers.zip(weights(numbers.size)).sumOf { (d, w) -> d * w } % 11
			return if (r < 2) 0 else 11 - r
		}
		val first = base + digit(base)
		return (first + digit(first)).joinToString("")
	}
}
