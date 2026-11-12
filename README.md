# soat-oficina-os-service

Serviço de **Ordens de Serviço (OS)** da oficina mecânica — Tech Challenge SOAT, Fase 4.
É um dos três microsserviços que substituem o monólito `soat-oficina-app`; os outros são
`soat-oficina-billing-service` e `soat-oficina-execution-service`. Abre a OS, guarda clientes,
veículos e o histórico de status, emite o JWT de staff que vale nos três serviços e **orquestra a
saga** entre eles por RabbitMQ. Está implementado o caminho feliz; as compensações (eventos de
falha) ainda não são tratadas e, por enquanto, só geram log `WARN`.

## Stack

- Kotlin 2.4.10, Spring Boot 4.1.1, Java 17, Gradle 9.4.0 (wrapper)
- Spring Web, Security (OAuth2 Resource Server, JWT HS256), Actuator, springdoc 3.1.0
- PostgreSQL 16 (JPA + Flyway) e RabbitMQ (Spring AMQP)
- Testes: JUnit 5, MockK, Testcontainers (PostgreSQL e RabbitMQ), JaCoCo (mínimo de 80% de linhas)
- Observabilidade: agente Java do New Relic na imagem, logs estruturados (logstash) no cluster

## Rodar local

Pré-requisitos: JDK 17, Docker. As dependências (PostgreSQL `os` na porta 5433 e RabbitMQ
na 5672) vêm do `docker-compose.yml` da plataforma, em `soat-oficina-infra-k8s/local/`:

```bash
docker compose --env-file ../.env.local -f ../soat-oficina-infra-k8s/local/docker-compose.yml up -d rabbitmq postgres-os
./gradlew bootRun
```

O `bootRun` ativa o perfil `local` (ambiente `hml` e um segredo JWT de desenvolvimento);
variáveis de ambiente continuam valendo por cima. A API sobe em `http://localhost:8081`:

- `GET /actuator/health` (público; `/actuator/health/liveness` e `/actuator/health/readiness`)
- `GET /swagger-ui.html` e `GET /v3/api-docs` (públicos)
- as rotas da seção [API](#api)

No perfil `local` os usuários de staff têm senha igual ao nome (`admin`, `atendente`, `tecnico`).

O token é HS256 assinado com SHA-256 do `APP_JWT_SECRET`, com `iss=oficina`,
`aud=oficina-api` e `env` igual ao `APP_ENVIRONMENT`. Rotas fora de `/api/**` e das públicas
são negadas.

## API

Todas em JSON; `/api/**` exige `Authorization: Bearer <jwt>` (basta estar autenticado). Erros no
formato `{ "message", "requestId" }`: 400 validação (inclui CPF/CNPJ e placa inválidos), 401 sem
token ou credencial errada, 404 OS inexistente, 409 regra de negócio ou transição de status inválida.

| Rota | O que faz |
|---|---|
| `POST /api/public/auth/login` | Público. `{ "username", "password" }` → `{ "accessToken", "expiresInSeconds" }`. Usuários `admin` (`ADMIN`), `atendente` (`ATTENDANT`) e `tecnico` (`TECHNICIAN`) |
| `POST /api/work-orders` | Abre a OS (`RECEIVED`), cria a saga e publica `GenerateQuote`. Cliente achado por `taxId` e veículo por placa, criados se não existirem; veículo de outro cliente é 409. Pelo menos um item em `services` ou `parts`; só `code` e `quantity` (catálogo e preço são do Billing). 201 |
| `GET /api/work-orders/{id}` | OS com cliente, veículo, itens, `quoteId`, `paymentId`, totais em centavos e `saga { status, step }`; o que ainda não chegou vem `null` |
| `GET /api/work-orders/{id}/history` | Mudanças de status em ordem cronológica: `fromStatus`, `toStatus`, `reason` (type do evento, `WorkOrderOpened` ou `DeliveryRegistered`), `occurredAt` |
| `POST /api/work-orders/{id}/delivery` | Só a partir de `FINALIZED`: leva a `DELIVERED` e conclui a saga (`COMPLETED`) |

Status da OS: `RECEIVED` (Recebida), `PENDING_APPROVAL` (Aguardando Aprovação), `AWAITING_PAYMENT`
(Aguardando Pagamento), `QUEUED` (Na Fila), `IN_DIAGNOSIS` (Diagnóstico), `IN_EXECUTION` (Execução),
`FINALIZED` (Finalizada), `DELIVERED` (Entregue), `CANCELLED` (Cancelada). Passos da saga:
`QUOTE_GENERATION`, `CUSTOMER_APPROVAL`, `PAYMENT`, `EXECUTION`, `DELIVERY`.

## Mensagens

Topologia declarada na subida (idempotente): exchange topic `oficina`, exchange direct `oficina.dlx`
e as filas `os.events` (`event.#`), `billing.commands` (`command.billing.#`) e
`execution.commands` (`command.execution.#`), cada uma com `x-dead-letter-exchange=oficina.dlx`,
`x-dead-letter-routing-key=<fila>.dlq` e a DLQ `<fila>.dlq`.

Envelope JSON (sem `__TypeId__`): `messageId`, `sagaId` (id da OS), `type`, `occurredAt`,
`correlationId` (nasce no `X-Correlation-Id` da abertura e se repete em toda a saga) e `payload`.
`messageId` e `type` vão também nas propriedades AMQP; o `correlationId`, no cabeçalho
`x-correlation-id` e no MDC (`requestId`) enquanto o evento é processado.

| Publica (depois do commit) | Quando |
|---|---|
| `GenerateQuote` → `command.billing.generate-quote` | OS aberta |
| `RequestPayment` → `command.billing.request-payment` | `QuoteApproved` |
| `StartExecution` → `command.execution.start-execution` | `PaymentApproved` |

| Consome de `os.events` | Efeito na OS |
|---|---|
| `QuoteGenerated` | `PENDING_APPROVAL`; guarda `quoteId` e totais |
| `QuoteApproved` | `AWAITING_PAYMENT` |
| `PaymentApproved` | `QUEUED`; guarda `paymentId` |
| `ExecutionStatusChanged` | `IN_DIAGNOSIS` ou `IN_EXECUTION` |
| `ExecutionFinished` | `FINALIZED` |
| `QuoteGenerationFailed`, `QuoteRejected`, `QuoteCancelled`, `PaymentRejected`, `PaymentRefunded`, `ExecutionFailed` | Nada ainda: log `WARN` e ack (compensações ainda não implementadas) |

O orquestrador (`SagaOrchestrator`) grava OS, saga (`saga_os`), histórico (`os_historico`) e o
`messageId` (`processed_message`) na mesma transação; mensagem repetida é ignorada com ack, e
evento que não se aplica ao status atual é descartado com log `WARN`. Falha no processamento tem
3 tentativas (500 ms, depois 1 s) e vai para `os.events.dlq`; JSON inválido, `type` fora do
contrato ou payload inválido vão direto para a DLQ.

## Testes

```bash
./gradlew check
```

Roda os testes unitários (domínio, orquestrador, envelope) e os de integração (contexto completo
contra PostgreSQL e RabbitMQ reais, via Testcontainers; precisa do Docker). O
`SagaFlowIntegrationTest` simula o Billing e o Execution publicando os eventos no exchange `oficina`
e lendo os comandos de filas de teste: leva uma OS de `RECEIVED` a `DELIVERED` e confere o
histórico, a idempotência, a DLQ e o retry. Gera o JaCoCo em
`build/reports/jacoco/test/` (XML e HTML) e falha se a cobertura de linhas do serviço inteiro
ficar abaixo de 80%. Só a classe principal fica fora da conta (a mesma exclusão vai para o Sonar).

## Deploy no kind

O cluster `oficina` (contexto `kind-oficina`), o namespace `oficina`, o RabbitMQ e o Secret
`os-service-secrets` vêm de `soat-oficina-infra-k8s/local/` (`up.sh` e `secrets.sh`).

```bash
scripts/deploy-kind.sh all
curl http://localhost:30081/actuator/health
```

Subcomandos de `scripts/deploy-kind.sh`:

| Subcomando | O que faz |
|---|---|
| `build` | `docker build` de `soat-oficina-os-service:$IMAGE_TAG` |
| `load` | `kind load docker-image` no cluster `oficina` |
| `apply` | overlay kustomize temporário com a tag e `kubectl apply -k` |
| `wait` | `kubectl rollout status` do banco e do serviço |
| `smoke` | `GET /actuator/health` no NodePort 30081 até `UP` |
| `rollback` | `kubectl rollout undo` + espera (no primeiro deploy só avisa) |
| `diagnose` | pods, eventos, describe e logs |
| `all` | build, load, apply, wait e smoke; se falhar, rollback e diagnóstico |

Variáveis: `IMAGE_TAG` (padrão: SHA curto do git ou `local`), `KIND_CLUSTER=oficina`,
`KUBE_CONTEXT=kind-oficina`, `NAMESPACE=oficina`, `ROLLOUT_TIMEOUT=300s`.

No GitHub, o workflow `Deploy` roda o mesmo script no runner self-hosted (`oficina-kind`)
depois de um `CI` verde de push na `main`.

## Configuração

| Variável | Origem no cluster | Padrão fora do cluster |
|---|---|---|
| `SERVER_PORT` | ConfigMap (`8080`) | `8081` |
| `APP_ENVIRONMENT` | ConfigMap (`hml`) | obrigatório (perfil `local`: `hml`) |
| `APP_JWT_SECRET` | Secret | obrigatório (perfil `local`: segredo de desenvolvimento) |
| `APP_JWT_EXPIRATION_MINUTES` | ConfigMap (`60`) | `60` |
| `APP_SECURITY_ADMIN_PASSWORD` / `_ATTENDANT_` / `_TECHNICIAN_` | Secret | obrigatórias (perfil `local`: igual ao nome do usuário) |
| `SPRING_DATASOURCE_URL` | ConfigMap (`jdbc:postgresql://os-postgres:5432/os`) | `jdbc:postgresql://localhost:5433/os` |
| `SPRING_DATASOURCE_USERNAME` | ConfigMap (`os`) | `os` |
| `SPRING_DATASOURCE_PASSWORD` | Secret | `oficina` |
| `SPRING_RABBITMQ_HOST` / `_PORT` / `_USERNAME` | ConfigMap (`rabbitmq` / `5672` / `oficina`) | `localhost` / `5672` / `oficina` |
| `SPRING_RABBITMQ_PASSWORD` | Secret | `oficina` |
| `NEW_RELIC_APP_NAME` e demais `NEW_RELIC_*` | ConfigMap (`oficina-os-service`) | — |
| `NEW_RELIC_LICENSE_KEY` | Secret (vazio desliga o agente) | — |

### Contrato do Secret `os-service-secrets`

| Chave | Uso |
|---|---|
| `APP_JWT_SECRET` | segredo HS256 compartilhado entre os serviços (mínimo 32 bytes) |
| `SPRING_DATASOURCE_PASSWORD` | senha da aplicação **e** do PostgreSQL `os-postgres` |
| `SPRING_RABBITMQ_PASSWORD` | senha do usuário `oficina` no RabbitMQ |
| `APP_SECURITY_ADMIN_PASSWORD` | senha do usuário `admin` no login de staff |
| `APP_SECURITY_ATTENDANT_PASSWORD` | senha do usuário `atendente` |
| `APP_SECURITY_TECHNICIAN_PASSWORD` | senha do usuário `tecnico` |
| `NEW_RELIC_LICENSE_KEY` | license key do New Relic; vazio desliga o agente |

Modelo só com placeholders em `k8s/secret.example.yaml` (fora do `kustomization.yaml`).

## Qualidade

O job `ci / build` roda `./gradlew check` e, quando o secret `SONAR_TOKEN` existe, a análise
no SonarCloud (`DevEgF_soat-oficina-os-service`, organização `devegf-soat-oficina`) com
`-Dsonar.qualitygate.wait=true`. O job `ci / image` faz o build da imagem, sem push.

## Proteção da `main`

`scripts/configure-github.sh` aplica, via GitHub CLI, o ruleset `soat-protection-main`
(pull request obrigatório, checks `ci / build` e `ci / image` obrigatórios, sem force-push e
sem deleção), exige aprovação para workflows de PRs de fork e grava o secret `SONAR_TOKEN`
quando a variável está no ambiente. O deploy (`deploy / kind`) só roda depois de um CI verde
de push na `main`, em runner self-hosted registrado por `local/runners.sh` do repositório
`soat-oficina-infra-k8s`.
