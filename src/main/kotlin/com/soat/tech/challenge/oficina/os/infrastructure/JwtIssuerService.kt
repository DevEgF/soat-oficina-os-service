package com.soat.tech.challenge.oficina.os.infrastructure

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.soat.tech.challenge.oficina.os.domain.port.IssuedToken
import com.soat.tech.challenge.oficina.os.domain.port.TokenIssuerPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.Date
import javax.crypto.SecretKey

/**
 * Issues the HS256 token accepted by the three services (same APP_JWT_SECRET, issuer oficina,
 * audience oficina-api, claim env). Scopes come from the user's SCOPE_* authorities.
 */
@Service
class JwtIssuerService(
	private val jwtSigningKey: SecretKey,
	private val userDetailsService: UserDetailsService,
	private val appEnvironment: AppEnvironment,
	private val clock: Clock,
	@Value("\${app.jwt.expiration-minutes:60}") private val expirationMinutes: Long,
) : TokenIssuerPort {

	override fun issueForUser(username: String): IssuedToken {
		val user = userDetailsService.loadUserByUsername(username)
		val scopes = user.authorities.mapNotNull { a -> a.authority?.removePrefix("SCOPE_")?.takeIf { it.isNotEmpty() } }
		val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
		val exp = now.plus(expirationMinutes, ChronoUnit.MINUTES)
		val claims = JWTClaimsSet.Builder()
			.issuer(ISSUER)
			.audience(AUDIENCE)
			.issueTime(Date.from(now))
			.expirationTime(Date.from(exp))
			.subject(username)
			.claim("env", appEnvironment.value)
			.claim("scope", scopes)
			.build()
		val jwt = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
		jwt.sign(MACSigner(jwtSigningKey.encoded))
		return IssuedToken(jwt.serialize(), ChronoUnit.SECONDS.between(now, exp))
	}

	private companion object {
		const val ISSUER = "oficina"
		const val AUDIENCE = "oficina-api"
	}
}
