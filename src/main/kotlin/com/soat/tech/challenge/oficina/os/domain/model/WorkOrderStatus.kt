package com.soat.tech.challenge.oficina.os.domain.model

/**
 * Work order lifecycle after the split into services (Fase 4). Enum names are persisted and exposed by the API.
 */
enum class WorkOrderStatus(val label: String) {
	RECEIVED("Recebida"),
	PENDING_APPROVAL("Aguardando Aprovação"),
	AWAITING_PAYMENT("Aguardando Pagamento"),
	QUEUED("Na Fila"),
	IN_DIAGNOSIS("Diagnóstico"),
	IN_EXECUTION("Execução"),
	FINALIZED("Finalizada"),
	DELIVERED("Entregue"),
	CANCELLED("Cancelada");

	val terminal: Boolean get() = this == DELIVERED || this == CANCELLED
}
