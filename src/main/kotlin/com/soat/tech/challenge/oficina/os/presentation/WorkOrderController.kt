package com.soat.tech.challenge.oficina.os.presentation

import com.soat.tech.challenge.oficina.os.application.WorkOrderApplicationService
import com.soat.tech.challenge.oficina.os.infrastructure.web.CorrelationIdFilter
import com.soat.tech.challenge.oficina.os.presentation.dto.CreateWorkOrderRequest
import com.soat.tech.challenge.oficina.os.presentation.dto.StatusHistoryResponse
import com.soat.tech.challenge.oficina.os.presentation.dto.WorkOrderResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/work-orders")
@Tag(name = "Ordens de serviço", description = "Abertura, consulta, histórico e entrega; a saga anda pelos eventos")
class WorkOrderController(private val service: WorkOrderApplicationService) {

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Abre a OS (RECEIVED) e pede o orçamento ao Billing (GenerateQuote)")
	fun open(@Valid @RequestBody request: CreateWorkOrderRequest): WorkOrderResponse {
		// Born here and reused by every message of the saga.
		val correlationId = MDC.get(CorrelationIdFilter.REQUEST_ID_KEY) ?: UUID.randomUUID().toString()
		return service.open(request.toCommand(correlationId)).toResponse()
	}

	@GetMapping("/{id}")
	@Operation(summary = "Consulta a OS, com totais do orçamento e estado da saga")
	fun get(@PathVariable id: UUID): WorkOrderResponse = service.get(id).toResponse()

	@GetMapping("/{id}/history")
	@Operation(summary = "Mudanças de status em ordem cronológica")
	fun history(@PathVariable id: UUID): List<StatusHistoryResponse> = service.history(id).map { it.toResponse() }

	@PostMapping("/{id}/delivery")
	@Operation(summary = "Registra a entrega (FINALIZED -> DELIVERED) e conclui a saga")
	fun delivery(@PathVariable id: UUID): WorkOrderResponse = service.registerDelivery(id).toResponse()
}
