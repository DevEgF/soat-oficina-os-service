package com.soat.tech.challenge.oficina.os.infrastructure

import com.soat.tech.challenge.oficina.os.support.TestJwt
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StaffAuthenticationTest {

	private fun environment(vararg profiles: String) = MockEnvironment().apply { setActiveProfiles(*profiles) }

	private fun credentials(
		admin: String = "admin-strong",
		attendant: String = "attendant-strong",
		technician: String = "technician-strong",
		vararg profiles: String,
	) = StaffCredentials(admin, attendant, technician, environment(*profiles))

	@Test
	fun `passwords are trimmed and returned per user`() {
		val credentials = credentials(admin = " admin-strong ")
		assertEquals("admin-strong", credentials.passwordFor("admin"))
		assertEquals("attendant-strong", credentials.passwordFor("atendente"))
		assertEquals("technician-strong", credentials.passwordFor("tecnico"))
	}

	@Test
	fun `blank or unresolved passwords are refused`() {
		assertFailsWith<IllegalArgumentException> { credentials(admin = " ") }
		assertFailsWith<IllegalArgumentException> { credentials(technician = "\${APP_SECURITY_TECHNICIAN_PASSWORD}") }
	}

	@Test
	fun `default passwords only under local or test profiles`() {
		assertFailsWith<IllegalArgumentException> { credentials(admin = "admin") }
		assertFailsWith<IllegalArgumentException> { credentials("admin", "atendente", "tecnico", "local", "docker") }
		assertEquals("tecnico", credentials("admin", "atendente", "tecnico", "test").passwordFor("tecnico"))
		assertEquals("admin", credentials("admin", "atendente", "tecnico", "local").passwordFor("admin"))
	}

	@Test
	fun `issued token is accepted by the decoder of the three services with the user scope`() {
		val security = SecurityConfiguration()
		val encoder = security.passwordEncoder()
		val users = security.userDetailsService(credentials(), encoder)
		val signingSecret = JwtSigningSecret(
			MockEnvironment().withProperty("app.jwt.secret", TestJwt.RAW_SECRET).apply { setActiveProfiles("test") },
		)
		val jwt = JwtConfiguration(signingSecret, AppEnvironment("hml"))
		val now = Instant.now()
		val issuer = JwtIssuerService(jwt.jwtSigningKey(), users, AppEnvironment("hml"), Clock.fixed(now, ZoneOffset.UTC), 60)

		val issued = issuer.issueForUser("atendente")

		assertEquals(3600, issued.expiresInSeconds)
		val decoded = jwt.jwtDecoder().decode(issued.token)
		assertEquals("atendente", decoded.subject)
		assertEquals(listOf("ATTENDANT"), decoded.getClaimAsStringList("scope"))
		assertEquals("hml", decoded.getClaimAsString("env"))
		assertEquals(listOf("oficina-api"), decoded.audience)

		val manager = security.authenticationManager(users, encoder)
		assertEquals("admin", manager.authenticate(UsernamePasswordAuthenticationToken("admin", "admin-strong")).name)
		assertFailsWith<BadCredentialsException> { manager.authenticate(UsernamePasswordAuthenticationToken("admin", "wrong")) }
		assertFailsWith<BadCredentialsException> { manager.authenticate(UsernamePasswordAuthenticationToken("ghost", "x")) }
	}
}
