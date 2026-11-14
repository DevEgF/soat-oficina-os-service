# language: pt
Funcionalidade: Fluxo da ordem de serviço conduzido pela saga
  O OS Service abre a OS e orquestra a saga com o Billing e o Execution por mensagens.
  Aqui o Billing e o Execution são simulados: os eventos deles são publicados no exchange
  "oficina" e os comandos do OS Service são lidos de filas de teste ligadas ao mesmo exchange.
  PostgreSQL e RabbitMQ são reais (contêineres de teste).

  Contexto:
    Dado que o atendente está autenticado

  Cenário: Caminho feliz, da abertura da OS até a entrega
    Quando o atendente abre uma OS
    Então a OS fica "RECEIVED"
    E o OS Service pede ao Billing "GenerateQuote"
    Quando o Billing publica "QuoteGenerated" com total de 21000 centavos
    Então a OS fica "PENDING_APPROVAL"
    Quando o Billing publica "QuoteApproved"
    Então a OS fica "AWAITING_PAYMENT"
    E o OS Service pede ao Billing "RequestPayment"
    Quando o Billing publica "PaymentApproved"
    Então a OS fica "QUEUED"
    E o OS Service pede ao Execution "StartExecution"
    Quando o Execution informa que a execução está em "IN_DIAGNOSIS"
    Então a OS fica "IN_DIAGNOSIS"
    Quando o Execution informa que a execução está em "IN_EXECUTION"
    Então a OS fica "IN_EXECUTION"
    Quando o Execution publica "ExecutionFinished"
    Então a OS fica "FINALIZED"
    Quando o atendente registra a entrega
    Então a OS fica "DELIVERED"
    E a saga fica "COMPLETED"
    E o histórico da OS tem as etapas:
      | de               | para             | motivo                 |
      |                  | RECEIVED         | WorkOrderOpened        |
      | RECEIVED         | PENDING_APPROVAL | QuoteGenerated         |
      | PENDING_APPROVAL | AWAITING_PAYMENT | QuoteApproved          |
      | AWAITING_PAYMENT | QUEUED           | PaymentApproved        |
      | QUEUED           | IN_DIAGNOSIS     | ExecutionStatusChanged |
      | IN_DIAGNOSIS     | IN_EXECUTION     | ExecutionStatusChanged |
      | IN_EXECUTION     | FINALIZED        | ExecutionFinished      |
      | FINALIZED        | DELIVERED        | DeliveryRegistered     |

  Cenário: Pagamento recusado cancela a OS e desfaz o orçamento
    Dada uma OS com o orçamento aprovado, aguardando o pagamento
    Quando o Billing publica "PaymentRejected" com o motivo "cc_rejected_other_reason"
    Então a OS fica "CANCELLED" com o motivo de cancelamento "PaymentRejected"
    E a saga fica "COMPENSATING" com a compensação "CANCEL_QUOTE" pendente
    E o OS Service pede ao Billing "CancelQuote" com o motivo "PaymentRejected"
    Quando o Billing publica "QuoteCancelled"
    Então a saga fica "COMPENSATED"
    E a OS continua "CANCELLED" e o histórico termina em "PaymentRejected"
