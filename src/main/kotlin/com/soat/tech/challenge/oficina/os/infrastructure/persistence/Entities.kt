package com.soat.tech.challenge.oficina.os.infrastructure.persistence

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

// JPA mapping of V1__init.sql. Associations are plain id columns (foreign keys live in the schema).

@Entity
@Table(name = "clientes")
class CustomerEntity(
	@Id
	@Column(length = 36)
	var id: String = "",
	@Column(nullable = false, unique = true, length = 14, name = "documento")
	var documentDigits: String = "",
	@Column(nullable = false, name = "nome")
	var name: String = "",
	@Column
	var email: String? = null,
	@Column(length = 50, name = "telefone")
	var phone: String? = null,
)

@Entity
@Table(name = "veiculos")
class VehicleEntity(
	@Id
	@Column(length = 36)
	var id: String = "",
	@Column(nullable = false, length = 36, name = "cliente_id")
	var customerId: String = "",
	@Column(nullable = false, unique = true, length = 10, name = "placa")
	var licensePlate: String = "",
	@Column(nullable = false, length = 100, name = "marca")
	var brand: String = "",
	@Column(nullable = false, length = 100, name = "modelo")
	var model: String = "",
	@Column(nullable = false, name = "ano")
	var year: Int = 0,
)

/** Line of a work order: catalog code and quantity only (prices belong to Billing). */
@Embeddable
class WorkOrderLineEmbeddable(
	@Column(nullable = false, length = 100, name = "codigo")
	var code: String = "",
	@Column(nullable = false, name = "quantidade")
	var quantity: Int = 0,
)

@Entity
@Table(name = "ordens_servico")
class WorkOrderEntity(
	@Id
	@Column(length = 36)
	var id: String = "",
	@Column(nullable = false, unique = true, length = 64, name = "codigo_acompanhamento")
	var trackingCode: String = "",
	@Column(nullable = false, length = 36, name = "cliente_id")
	var customerId: String = "",
	@Column(nullable = false, length = 36, name = "veiculo_id")
	var vehicleId: String = "",
	@Column(nullable = false, length = 40)
	var status: String = "",
	@Column(name = "valor_servicos_centavos")
	var servicesTotalCents: Long? = null,
	@Column(name = "valor_pecas_centavos")
	var partsTotalCents: Long? = null,
	@Column(name = "valor_total_centavos")
	var totalCents: Long? = null,
	@Column(nullable = false, name = "criado_em")
	var createdAt: Instant = Instant.EPOCH,
	@Column(nullable = false, name = "atualizado_em")
	var updatedAt: Instant = Instant.EPOCH,
	@Column(name = "orcamento_gerado_em")
	var quotedAt: Instant? = null,
	@Column(name = "aprovado_em")
	var approvedAt: Instant? = null,
	@Column(name = "pago_em")
	var paidAt: Instant? = null,
	@Column(name = "diagnostico_iniciado_em")
	var diagnosisStartedAt: Instant? = null,
	@Column(name = "execucao_iniciada_em")
	var repairStartedAt: Instant? = null,
	@Column(name = "finalizada_em")
	var finishedAt: Instant? = null,
	@Column(name = "entregue_em")
	var deliveredAt: Instant? = null,
	@Column(name = "cancelada_em")
	var cancelledAt: Instant? = null,
	@Version
	@Column(name = "versao")
	var version: Long? = null,
	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "ordem_servico_linhas_servico", joinColumns = [JoinColumn(name = "ordem_servico_id")])
	@OrderColumn(name = "posicao")
	var serviceLines: MutableList<WorkOrderLineEmbeddable> = mutableListOf(),
	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "ordem_servico_linhas_peca", joinColumns = [JoinColumn(name = "ordem_servico_id")])
	@OrderColumn(name = "posicao")
	var partLines: MutableList<WorkOrderLineEmbeddable> = mutableListOf(),
)

@Entity
@Table(name = "saga_os")
class WorkOrderSagaEntity(
	@Id
	@Column(length = 36)
	var id: String = "",
	@Column(nullable = false, length = 20)
	var status: String = "",
	@Column(nullable = false, length = 40, name = "passo_atual")
	var step: String = "",
	@Column(length = 36, name = "quote_id")
	var quoteId: String? = null,
	@Column(length = 36, name = "payment_id")
	var paymentId: String? = null,
	@Column(nullable = false, length = 64, name = "correlation_id")
	var correlationId: String = "",
	@Column(nullable = false, name = "criado_em")
	var createdAt: Instant = Instant.EPOCH,
	@Column(nullable = false, name = "atualizado_em")
	var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "os_historico")
class StatusHistoryEntity(
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = null,
	@Column(nullable = false, length = 36, name = "ordem_servico_id")
	var workOrderId: String = "",
	@Column(length = 40, name = "status_anterior")
	var fromStatus: String? = null,
	@Column(nullable = false, length = 40, name = "status_novo")
	var toStatus: String = "",
	@Column(nullable = false, length = 64, name = "motivo")
	var reason: String = "",
	@Column(length = 36, name = "message_id")
	var messageId: String? = null,
	@Column(nullable = false, name = "ocorrido_em")
	var occurredAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "processed_message")
class ProcessedMessageEntity(
	@Id
	@Column(length = 36, name = "message_id")
	var messageId: String = "",
	@Column(nullable = false, length = 64)
	var type: String = "",
	@Column(nullable = false, length = 36, name = "saga_id")
	var sagaId: String = "",
	@Column(nullable = false, name = "processed_at")
	var processedAt: Instant = Instant.EPOCH,
)
