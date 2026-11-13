package com.soat.tech.challenge.oficina.os.bdd

import com.soat.tech.challenge.oficina.os.application.messaging.MessageType
import com.soat.tech.challenge.oficina.os.integration.SagaIntegrationTestSupport
import io.cucumber.datatable.DataTable
import io.cucumber.java.pt.Dada
import io.cucumber.java.pt.Dado
import io.cucumber.java.pt.Entao
import io.cucumber.java.pt.Quando
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.UUID

/**
 * Steps of features/fluxo-os.feature. One instance per scenario (cucumber-glue scope), so the ids below
 * belong to the scenario. HTTP goes to this service; Billing and Execution are simulated on the broker.
 */
class WorkOrderFlowSteps : SagaIntegrationTestSupport() {

	private val correlationId = "bdd-${UUID.randomUUID()}"
	private val quoteId = UUID.randomUUID().toString()
	private val paymentId = UUID.randomUUID().toString()
	private var totalCents = DEFAULT_TOTAL_CENTS
	private lateinit var workOrderId: String

	@Dado("que o atendente está autenticado")
	fun attendantIsLoggedIn() {
		setUpSaga()
	}

	@Quando("o atendente abre uma OS")
	fun attendantOpensWorkOrder() {
		workOrderId = openWorkOrder(correlationId)
	}

	@Dada("uma OS com o orçamento aprovado, aguardando o pagamento")
	fun workOrderAwaitingPayment() {
		attendantOpensWorkOrder()
		quoteGenerated(workOrderId, quoteId, totalCents)
		awaitStatus(workOrderId, "PENDING_APPROVAL")
		quoteApproved(workOrderId, quoteId, totalCents)
		awaitStatus(workOrderId, "AWAITING_PAYMENT")
		awaitCommands(BILLING_TEST_QUEUE, workOrderId, listOf("GenerateQuote", "RequestPayment"))
	}

	@Quando("o Billing publica {string} com total de {long} centavos")
	fun billingPublishesQuote(type: String, total: Long) {
		assertEquals("QuoteGenerated", type)
		totalCents = total
		quoteGenerated(workOrderId, quoteId, total)
	}

	@Quando("o Billing publica {string}")
	fun billingPublishes(type: String) {
		when (type) {
			"QuoteApproved" -> quoteApproved(workOrderId, quoteId, totalCents)
			"PaymentApproved" -> paymentApproved(workOrderId, quoteId, paymentId)
			"QuoteCancelled" -> quoteCancelled(workOrderId, quoteId)
			else -> throw IllegalArgumentException("Billing event without a step: $type")
		}
	}

	@Quando("o Billing publica {string} com o motivo {string}")
	fun billingPublishesRejection(type: String, reason: String) {
		assertEquals("PaymentRejected", type)
		publishEvent(
			type, MessageType.PAYMENT_REJECTED.routingKey, workOrderId,
			"""{"workOrderId":"$workOrderId","quoteId":"$quoteId","paymentId":"$paymentId","reason":"$reason"}""",
		)
	}

	@Quando("o Execution informa que a execução está em {string}")
	fun executionStatusChanges(status: String) {
		executionStatusChanged(workOrderId, status)
	}

	@Quando("o Execution publica {string}")
	fun executionPublishes(type: String) {
		assertEquals("ExecutionFinished", type)
		publishEvent(
			type, MessageType.EXECUTION_FINISHED.routingKey, workOrderId,
			"""{"workOrderId":"$workOrderId","finishedAt":"2026-11-12T18:00:00.000Z"}""",
		)
	}

	@Quando("o atendente registra a entrega")
	fun attendantRegistersDelivery() {
		val response = post("/api/work-orders/$workOrderId/delivery", null, token)
		assertEquals(200, response.statusCode(), response.body())
	}

	@Entao("a OS fica {string}")
	fun workOrderStatusIs(status: String) {
		awaitStatus(workOrderId, status)
	}

	@Entao("a OS fica {string} com o motivo de cancelamento {string}")
	fun workOrderIsCancelled(status: String, reason: String) {
		assertEquals(reason, awaitStatus(workOrderId, status)["cancellationReason"].asText())
	}

	@Entao("a OS continua {string} e o histórico termina em {string}")
	fun workOrderStaysCancelled(status: String, reason: String) {
		assertEquals(status, body(get("/api/work-orders/$workOrderId", token))["status"].asText())
		val history = history(workOrderId)
		assertEquals(reason, history.last().third)
		assertEquals(1, history.count { it.second == status })
	}

	@Entao("a saga fica {string}")
	fun sagaStatusIs(status: String) {
		awaitSagaStatus(workOrderId, status)
	}

	@Entao("a saga fica {string} com a compensação {string} pendente")
	fun sagaWaitsFor(status: String, compensation: String) {
		assertEquals(listOf(compensation), pendingCompensations(awaitSagaStatus(workOrderId, status)))
	}

	@Entao("o OS Service pede ao Billing {string}")
	fun commandToBilling(type: String) {
		awaitContractCommand(BILLING_TEST_QUEUE, type)
	}

	@Entao("o OS Service pede ao Billing {string} com o motivo {string}")
	fun compensationToBilling(type: String, reason: String) {
		val command = awaitContractCommand(BILLING_TEST_QUEUE, type)
		assertEquals(quoteId, command.payload["quoteId"].asText())
		assertEquals(reason, command.payload["reason"].asText())
	}

	@Entao("o OS Service pede ao Execution {string}")
	fun commandToExecution(type: String) {
		awaitContractCommand(EXECUTION_TEST_QUEUE, type)
	}

	@Entao("o histórico da OS tem as etapas:")
	fun historyHasSteps(steps: DataTable) {
		val expected = steps.asMaps().map { row ->
			Triple(row["de"]?.takeIf { it.isNotBlank() }, row.getValue("para").orEmpty(), row.getValue("motivo").orEmpty())
		}
		assertEquals(expected, history(workOrderId))
	}

	/** Command of this saga with the routing key of the contract and the correlation id of the opening. */
	private fun awaitContractCommand(queue: String, type: String): ReceivedCommand {
		val command = awaitCommand(queue, type, workOrderId)
		assertEquals(MessageType.fromTypeName(type)?.routingKey, command.routingKey)
		assertEquals(correlationId, command.envelope["correlationId"].asText())
		assertEquals(correlationId, command.properties.getHeader<String>("x-correlation-id"))
		return command
	}

	private companion object {
		const val DEFAULT_TOTAL_CENTS = 21_000L
	}
}
