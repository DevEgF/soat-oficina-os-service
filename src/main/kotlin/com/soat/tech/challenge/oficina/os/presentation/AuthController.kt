package com.soat.tech.challenge.oficina.os.presentation

import com.soat.tech.challenge.oficina.os.domain.port.TokenIssuerPort
import com.soat.tech.challenge.oficina.os.presentation.dto.LoginRequest
import com.soat.tech.challenge.oficina.os.presentation.dto.LoginResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/public/auth")
@Tag(name = "Autenticação", description = "Login de staff; o token vale nos três serviços")
class AuthController(
	private val authenticationManager: AuthenticationManager,
	private val tokenIssuer: TokenIssuerPort,
) {

	@PostMapping("/login")
	@SecurityRequirements
	@Operation(summary = "Troca usuário e senha de staff (admin, atendente, tecnico) por um JWT HS256")
	fun login(@Valid @RequestBody request: LoginRequest): LoginResponse {
		authenticationManager.authenticate(UsernamePasswordAuthenticationToken(request.username, request.password))
		val issued = tokenIssuer.issueForUser(request.username)
		return LoginResponse(accessToken = issued.token, expiresInSeconds = issued.expiresInSeconds)
	}
}
