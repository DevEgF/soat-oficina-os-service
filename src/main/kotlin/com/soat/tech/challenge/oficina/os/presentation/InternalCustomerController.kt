package com.soat.tech.challenge.oficina.os.presentation

import com.soat.tech.challenge.oficina.os.application.CustomerLookupService
import com.soat.tech.challenge.oficina.os.domain.exception.NotFoundException
import com.soat.tech.challenge.oficina.os.presentation.dto.InternalCustomerResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Routes for other services, not for people: only a token with the INTERNAL scope gets in
 * (SecurityConfiguration). Nothing here logs the document.
 */
@RestController
@RequestMapping("/api/internal/customers")
@Tag(name = "Interno", description = "Consultas entre serviços; exigem JWT com escopo INTERNAL")
class InternalCustomerController(private val customers: CustomerLookupService) {

	@GetMapping("/{taxId}")
	@Operation(
		summary = "Cliente pelo CPF ou CNPJ (só dígitos), para o serviço de autenticação",
		description = "Não há bloqueio de cliente neste serviço: um cliente encontrado está sempre ACTIVE.",
		responses = [
			ApiResponse(responseCode = "200", description = "Cliente encontrado"),
			ApiResponse(
				responseCode = "400",
				description = "Documento com algo além de dígitos ou CPF/CNPJ inválido",
				content = [Content(schema = Schema(implementation = ErrorBody::class))],
			),
			ApiResponse(responseCode = "401", description = "Sem token ou token inválido", content = [Content()]),
			ApiResponse(responseCode = "403", description = "Token sem o escopo INTERNAL (staff ou cliente)", content = [Content()]),
			ApiResponse(
				responseCode = "404",
				description = "Nenhum cliente com esse documento",
				content = [Content(schema = Schema(implementation = ErrorBody::class))],
			),
		],
	)
	fun find(
		@Parameter(description = "CPF (11 dígitos) ou CNPJ (14 dígitos), sem pontuação", example = "52998224725")
		@PathVariable taxId: String,
	): InternalCustomerResponse {
		val customer = customers.findByTaxId(taxId) ?: throw NotFoundException("Customer not found")
		return InternalCustomerResponse(id = customer.id, status = InternalCustomerResponse.STATUS_ACTIVE)
	}
}
