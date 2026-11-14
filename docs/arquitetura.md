# Arquitetura do OS Service

O serviço segue camadas com dependência de fora para dentro: a apresentação chama a aplicação, a
aplicação usa o domínio e fala com o mundo por portas (interfaces), e a infraestrutura implementa essas
portas (banco, RabbitMQ, New Relic, segurança). O domínio não conhece Spring, JPA nem RabbitMQ. O papel do
serviço na saga entre os três microsserviços está no [README](../README.md#saga).

## Componentes por camada

```mermaid
flowchart TB
    subgraph apresentacao["Apresentação (presentation)"]
        WOC["WorkOrderController<br/>/api/work-orders"]
        AUC["AuthController<br/>/api/public/auth/login"]
        ICC["InternalCustomerController<br/>/api/internal/customers/{taxId}"]
        INF["InfoController<br/>/api/info"]
        REH["RestExceptionHandler"]
    end

    subgraph aplicacao["Aplicação (application)"]
        WOAS["WorkOrderApplicationService<br/>abre, consulta, entrega"]
        CLS["CustomerLookupService"]
        SO["SagaOrchestrator<br/>eventos, transições, próximos comandos"]
        ATS["ApprovalTimeoutService"]
        SL["SagaLifecycle<br/>logs do ciclo de vida, SagaTransition, SagaFinished"]
        ACP["AfterCommitPublisher"]
        SC["SagaCommands"]
        CP[["CommandPublisher (porta)"]]
        TR[["Tracing (porta)"]]
    end

    subgraph dominio["Domínio (domain)"]
        WO["WorkOrder<br/>máquina de estados da OS"]
        WOS["WorkOrderSaga<br/>status, passo, pendências"]
        CC["CancellationCause, Compensation"]
        VO["Customer, Vehicle, TaxDocument, LicensePlate"]
        RP[["Portas: repositórios, TokenIssuerPort"]]
    end

    subgraph infra["Infraestrutura e adaptadores (infrastructure)"]
        JPA["RepositoryAdapters + JPA"]
        OEL["OsEventsListener"]
        RCP["RabbitCommandPublisher"]
        EC["EnvelopeCodec"]
        TOP["MessagingTopology, ListenerRetryConfiguration"]
        SEC["SecurityConfiguration, JwtConfiguration"]
        JIS["JwtIssuerService"]
        NRT["NewRelicTracing"]
        SCH["ApprovalTimeoutScheduler"]
        CIF["CorrelationIdFilter"]
    end

    PG[("PostgreSQL os")]
    MQ[("RabbitMQ<br/>exchange oficina")]
    NR[("New Relic")]

    WOC --> WOAS
    ICC --> CLS
    AUC --> JIS
    WOAS --> SC
    WOAS --> ACP
    WOAS --> SL
    SO --> SC
    SO --> ACP
    SO --> SL
    ATS --> SO
    ACP --> CP
    SL --> TR
    WOAS --> WO
    WOAS --> WOS
    SO --> WO
    SO --> WOS
    SO --> CC
    CLS --> VO
    WOAS --> RP
    SO --> RP
    CLS --> RP

    JPA -. implementa .-> RP
    JIS -. implementa .-> RP
    RCP -. implementa .-> CP
    NRT -. implementa .-> TR
    OEL --> EC
    OEL --> SO
    OEL --> TR
    SCH --> ATS
    SCH --> TR
    RCP --> EC
    JPA --> PG
    RCP --> MQ
    MQ --> OEL
    TOP --> MQ
    NRT --> NR
    SEC -. valida o JWT .-> apresentacao
    CIF -. requestId no MDC .-> apresentacao
```

- **Apresentação:** valida a entrada (Bean Validation), converte DTOs e mapeia exceções para status HTTP.
  A rota interna só aceita o token com escopo `INTERNAL` (regra em `SecurityConfiguration`).
- **Aplicação:** cada caso de uso é uma transação. Comandos para os outros serviços são registrados no
  `AfterCommitPublisher` e só saem depois do commit; o `SagaLifecycle` também escreve depois do commit.
- **Domínio:** `WorkOrder` valida cada transição pelo status de origem; `WorkOrderSaga` segue o status da
  OS e, depois de uma falha, guarda as compensações pendentes; `CancellationCause` diz de que status cada
  falha pode vir e o que tem de ser desfeito.
- **Infraestrutura:** o consumidor `OsEventsListener` abre a transação do agente do New Relic, aceita os
  cabeçalhos de trace da mensagem, põe `requestId`, `sagaId`, `messageType` e `messageId` no MDC, decodifica
  o envelope e chama o orquestrador. `NewRelicTracing` é o único ponto que usa a API do New Relic.

## Participação na saga

Abertura da OS pela API e processamento de um evento do Billing ou do Execution, por dentro do serviço.

```mermaid
sequenceDiagram
    autonumber
    actor At as Atendente
    participant API as WorkOrderController
    participant App as WorkOrderApplicationService
    participant DB as PostgreSQL
    participant Pub as AfterCommitPublisher / RabbitCommandPublisher
    participant MQ as RabbitMQ (oficina)
    participant L as OsEventsListener
    participant O as SagaOrchestrator
    participant LC as SagaLifecycle

    At->>API: POST /api/work-orders (X-Correlation-Id)
    API->>App: open(command)
    App->>DB: clientes, veiculos, ordens_servico, saga_os, os_historico
    App->>Pub: GenerateQuote (para depois do commit)
    App->>LC: saga.started (para depois do commit)
    Note over App,DB: commit
    Pub->>MQ: GenerateQuote (command.billing.generate-quote)
    LC-->>LC: log saga.started, evento SagaTransition
    API-->>At: 201 OS RECEIVED

    MQ->>L: evento em os.events (por exemplo QuoteApproved)
    L->>L: abre a transação Message/os.events/QuoteApproved e aceita traceparent, tracestate e newrelic
    L->>L: MDC e decodificação do envelope (envelope inválido vai direto para a DLQ)
    L->>O: handle(evento)
    O->>DB: procura o messageId em processed_message (repetido termina aqui, com ack)
    O->>DB: SELECT ... FOR UPDATE em saga_os
    O->>O: transição da OS e da saga (evento fora de ordem é descartado com WARN)
    O->>DB: ordens_servico, saga_os, os_historico, processed_message
    O->>Pub: próximo comando (RequestPayment, StartExecution, CancelQuote, RefundPayment)
    O->>LC: mudança aplicada
    Note over O,DB: commit
    Pub->>MQ: comando com os cabeçalhos de trace
    LC-->>LC: logs do ciclo de vida, SagaTransition, SagaFinished no fim da saga
    L-->>MQ: ack (em erro, 3 tentativas e depois os.events.dlq)
```

O prazo de aprovação segue o mesmo caminho a partir do `ApprovalTimeoutScheduler` (transação
`Job/ApprovalTimeoutCheck`): `ApprovalTimeoutService` busca as OS vencidas e chama
`SagaOrchestrator.expireApproval` para cada uma, em transações separadas.

## Modelo de dados

```mermaid
erDiagram
    clientes ||--o{ veiculos : "possui"
    clientes ||--o{ ordens_servico : "abre"
    veiculos ||--o{ ordens_servico : "recebe"
    ordens_servico ||--o{ ordem_servico_linhas_servico : "serviços pedidos"
    ordens_servico ||--o{ ordem_servico_linhas_peca : "peças pedidas"
    ordens_servico ||--|| saga_os : "estado da saga"
    ordens_servico ||--o{ os_historico : "mudanças de status"

    clientes {
        varchar id PK
        varchar documento UK "CPF ou CNPJ, só dígitos"
        varchar nome
        varchar email
        varchar telefone
    }
    veiculos {
        varchar id PK
        varchar cliente_id FK
        varchar placa UK "normalizada"
        varchar marca
        varchar modelo
        int ano
    }
    ordens_servico {
        varchar id PK
        varchar codigo_acompanhamento UK
        varchar cliente_id FK
        varchar veiculo_id FK
        varchar status
        bigint valor_servicos_centavos
        bigint valor_pecas_centavos
        bigint valor_total_centavos
        timestamptz criado_em
        timestamptz atualizado_em
        timestamptz orcamento_gerado_em "referência do prazo de aprovação"
        timestamptz aprovado_em
        timestamptz pago_em
        timestamptz diagnostico_iniciado_em
        timestamptz execucao_iniciada_em
        timestamptz finalizada_em
        timestamptz entregue_em
        timestamptz cancelada_em
        varchar motivo_cancelamento
        bigint versao "lock otimista"
    }
    ordem_servico_linhas_servico {
        varchar ordem_servico_id PK, FK
        int posicao PK
        varchar codigo
        int quantidade
    }
    ordem_servico_linhas_peca {
        varchar ordem_servico_id PK, FK
        int posicao PK
        varchar codigo
        int quantidade
    }
    saga_os {
        varchar id PK, FK "igual ao id da OS"
        varchar status "RUNNING, COMPLETED, COMPENSATING, COMPENSATED"
        varchar passo_atual
        varchar quote_id
        varchar payment_id
        varchar correlation_id
        timestamptz criado_em
        timestamptz atualizado_em
        varchar motivo_falha
        boolean pendente_cancelar_orcamento
        boolean pendente_estornar_pagamento
    }
    os_historico {
        bigint id PK
        varchar ordem_servico_id FK
        varchar status_anterior
        varchar status_novo
        varchar motivo "type do evento ou ação"
        varchar message_id
        timestamptz ocorrido_em
    }
    processed_message {
        varchar message_id PK
        varchar type
        varchar saga_id
        timestamptz processed_at
    }
```

`processed_message` não tem chave estrangeira: um evento de uma OS desconhecida também é registrado, para
não ser processado de novo. As migrations ficam em `src/main/resources/db/migration` (`V1__init.sql` e
`V2__compensacoes_saga.sql`); uma mudança de esquema entra em uma migration nova.
