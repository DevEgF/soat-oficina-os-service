package com.soat.tech.challenge.oficina.os.infrastructure

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Staff passwords from the environment. A password equal to the user name (the development default)
 * is accepted only when every active profile is local or test.
 */
@Component
class StaffCredentials(
	@Value("\${app.security.admin.password}") adminPassword: String,
	@Value("\${app.security.attendant.password}") attendantPassword: String,
	@Value("\${app.security.technician.password}") technicianPassword: String,
	springEnvironment: Environment,
) {
	private val values: Map<String, String>

	init {
		val profiles = springEnvironment.activeProfiles
		val allowDevelopmentDefaults = profiles.isNotEmpty() && profiles.all { it == "local" || it == "test" }
		values = mapOf(
			ADMIN to adminPassword,
			ATTENDANT to attendantPassword,
			TECHNICIAN to technicianPassword,
		).mapValues { (username, password) ->
			password.trim().also {
				require(it.isNotBlank()) { "Password for $username must not be blank" }
				require(!it.isUnresolvedPlaceholder()) { "Password for $username must be configured" }
				require(allowDevelopmentDefaults || it != username) {
					"Default password is forbidden for $username outside local/test"
				}
			}
		}
	}

	fun passwordFor(username: String): String = values.getValue(username)

	private fun String.isUnresolvedPlaceholder(): Boolean = startsWith("\${") && endsWith("}")

	companion object {
		const val ADMIN = "admin"
		const val ATTENDANT = "atendente"
		const val TECHNICIAN = "tecnico"
	}
}
