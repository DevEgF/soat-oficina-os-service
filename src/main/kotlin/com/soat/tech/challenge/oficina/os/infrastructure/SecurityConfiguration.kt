package com.soat.tech.challenge.oficina.os.infrastructure

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

/**
 * Resource server (HS256 tokens validated with the shared secret) and token issuer for the staff:
 * this service is the one that signs the token used by the three services.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfiguration {

	@Bean
	fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

	@Bean
	fun userDetailsService(credentials: StaffCredentials, encoder: PasswordEncoder): UserDetailsService {
		fun user(username: String, scope: String) = User.builder()
			.username(username)
			.password(encoder.encode(credentials.passwordFor(username)))
			.authorities(scope)
			.build()
		return InMemoryUserDetailsManager(
			user(StaffCredentials.ADMIN, "SCOPE_ADMIN"),
			user(StaffCredentials.ATTENDANT, "SCOPE_ATTENDANT"),
			user(StaffCredentials.TECHNICIAN, "SCOPE_TECHNICIAN"),
		)
	}

	/**
	 * Explicit: with the OAuth2 resource server in the same filter chain, the default manager may not
	 * authenticate a UsernamePasswordAuthenticationToken on /login.
	 */
	@Bean
	fun authenticationManager(userDetailsService: UserDetailsService, passwordEncoder: PasswordEncoder): AuthenticationManager {
		val provider = DaoAuthenticationProvider(userDetailsService)
		provider.setPasswordEncoder(passwordEncoder)
		return ProviderManager(provider)
	}

	@Bean
	fun securityFilterChain(http: HttpSecurity, jwtDecoder: JwtDecoder): SecurityFilterChain {
		http.csrf { it.disable() }
		http.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
		http.authorizeHttpRequests { authorize ->
			authorize.requestMatchers(
				"/actuator/health",
				"/actuator/health/**",
				"/v3/api-docs",
				"/v3/api-docs/**",
				"/swagger-ui/**",
				"/swagger-ui.html",
				"/api/public/auth/login",
			).permitAll()
			// Service-to-service routes: only the internal token (scope INTERNAL) of the authentication service.
			authorize.requestMatchers("/api/internal/**").hasAuthority(INTERNAL_AUTHORITY)
			authorize.requestMatchers("/api/**").authenticated()
			authorize.anyRequest().denyAll()
		}
		http.oauth2ResourceServer { oauth2 -> oauth2.jwt { it.decoder(jwtDecoder) } }
		return http.build()
	}

	companion object {
		/** Authority of the token the authentication service signs for itself (scope ["INTERNAL"]). */
		const val INTERNAL_AUTHORITY = "SCOPE_INTERNAL"
	}
}
