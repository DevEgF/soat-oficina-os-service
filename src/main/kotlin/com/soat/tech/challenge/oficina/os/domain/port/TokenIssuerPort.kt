package com.soat.tech.challenge.oficina.os.domain.port

/** Token issued for an authenticated user and its lifetime in seconds. */
data class IssuedToken(val token: String, val expiresInSeconds: Long)

/** Outbound port for access tokens; the JWT details live in the infrastructure. */
interface TokenIssuerPort {
	fun issueForUser(username: String): IssuedToken
}
