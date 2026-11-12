-- Esquema do OS Service (Fase 4). Consolida do monólito só o que este serviço usa:
-- clientes, veículos e ordens de serviço. As linhas da OS guardam apenas código e quantidade
-- (catálogo e preço são do Billing). Saga, histórico e idempotência são novos.

CREATE TABLE clientes (
	id VARCHAR(36) NOT NULL PRIMARY KEY,
	documento VARCHAR(14) NOT NULL,
	nome VARCHAR(255) NOT NULL,
	email VARCHAR(255),
	telefone VARCHAR(50)
);

CREATE UNIQUE INDEX uk_clientes_documento ON clientes (documento);

CREATE TABLE veiculos (
	id VARCHAR(36) NOT NULL PRIMARY KEY,
	cliente_id VARCHAR(36) NOT NULL,
	placa VARCHAR(10) NOT NULL,
	marca VARCHAR(100) NOT NULL,
	modelo VARCHAR(100) NOT NULL,
	ano INT NOT NULL,
	CONSTRAINT fk_veiculos_cliente FOREIGN KEY (cliente_id) REFERENCES clientes (id)
);

CREATE UNIQUE INDEX uk_veiculos_placa ON veiculos (placa);
CREATE INDEX idx_veiculos_cliente ON veiculos (cliente_id);

CREATE TABLE ordens_servico (
	id VARCHAR(36) NOT NULL PRIMARY KEY,
	codigo_acompanhamento VARCHAR(64) NOT NULL,
	cliente_id VARCHAR(36) NOT NULL,
	veiculo_id VARCHAR(36) NOT NULL,
	status VARCHAR(40) NOT NULL,
	-- Totais chegam no QuoteGenerated; nulos até lá.
	valor_servicos_centavos BIGINT,
	valor_pecas_centavos BIGINT,
	valor_total_centavos BIGINT,
	criado_em TIMESTAMP WITH TIME ZONE NOT NULL,
	atualizado_em TIMESTAMP WITH TIME ZONE NOT NULL,
	orcamento_gerado_em TIMESTAMP WITH TIME ZONE,
	aprovado_em TIMESTAMP WITH TIME ZONE,
	pago_em TIMESTAMP WITH TIME ZONE,
	diagnostico_iniciado_em TIMESTAMP WITH TIME ZONE,
	execucao_iniciada_em TIMESTAMP WITH TIME ZONE,
	finalizada_em TIMESTAMP WITH TIME ZONE,
	entregue_em TIMESTAMP WITH TIME ZONE,
	cancelada_em TIMESTAMP WITH TIME ZONE,
	versao BIGINT NOT NULL DEFAULT 0,
	CONSTRAINT fk_os_cliente FOREIGN KEY (cliente_id) REFERENCES clientes (id),
	CONSTRAINT fk_os_veiculo FOREIGN KEY (veiculo_id) REFERENCES veiculos (id),
	CONSTRAINT ck_os_status CHECK (status IN (
		'RECEIVED', 'PENDING_APPROVAL', 'AWAITING_PAYMENT', 'QUEUED', 'IN_DIAGNOSIS',
		'IN_EXECUTION', 'FINALIZED', 'DELIVERED', 'CANCELLED'
	))
);

CREATE UNIQUE INDEX uk_os_codigo_acompanhamento ON ordens_servico (codigo_acompanhamento);
CREATE INDEX idx_os_status_criado_em ON ordens_servico (status, criado_em);

CREATE TABLE ordem_servico_linhas_servico (
	ordem_servico_id VARCHAR(36) NOT NULL,
	posicao INT NOT NULL,
	codigo VARCHAR(100) NOT NULL,
	quantidade INT NOT NULL CHECK (quantidade > 0),
	PRIMARY KEY (ordem_servico_id, posicao),
	CONSTRAINT fk_oss_os FOREIGN KEY (ordem_servico_id) REFERENCES ordens_servico (id) ON DELETE CASCADE
);

CREATE TABLE ordem_servico_linhas_peca (
	ordem_servico_id VARCHAR(36) NOT NULL,
	posicao INT NOT NULL,
	codigo VARCHAR(100) NOT NULL,
	quantidade INT NOT NULL CHECK (quantidade > 0),
	PRIMARY KEY (ordem_servico_id, posicao),
	CONSTRAINT fk_osp_os FOREIGN KEY (ordem_servico_id) REFERENCES ordens_servico (id) ON DELETE CASCADE
);

-- Estado da saga orquestrada: id da saga = id da OS.
CREATE TABLE saga_os (
	id VARCHAR(36) NOT NULL PRIMARY KEY,
	status VARCHAR(20) NOT NULL,
	passo_atual VARCHAR(40) NOT NULL,
	quote_id VARCHAR(36),
	payment_id VARCHAR(36),
	correlation_id VARCHAR(64) NOT NULL,
	criado_em TIMESTAMP WITH TIME ZONE NOT NULL,
	atualizado_em TIMESTAMP WITH TIME ZONE NOT NULL,
	CONSTRAINT fk_saga_os FOREIGN KEY (id) REFERENCES ordens_servico (id) ON DELETE CASCADE,
	CONSTRAINT ck_saga_status CHECK (status IN ('RUNNING', 'COMPLETED', 'COMPENSATING', 'COMPENSATED'))
);

-- Uma linha por mudança de status da OS; motivo = type do evento ou ação HTTP.
CREATE TABLE os_historico (
	id BIGSERIAL PRIMARY KEY,
	ordem_servico_id VARCHAR(36) NOT NULL,
	status_anterior VARCHAR(40),
	status_novo VARCHAR(40) NOT NULL,
	motivo VARCHAR(64) NOT NULL,
	message_id VARCHAR(36),
	ocorrido_em TIMESTAMP WITH TIME ZONE NOT NULL,
	CONSTRAINT fk_historico_os FOREIGN KEY (ordem_servico_id) REFERENCES ordens_servico (id) ON DELETE CASCADE
);

CREATE INDEX idx_os_historico_os ON os_historico (ordem_servico_id, id);

-- Idempotência do consumidor: messageId já processado (gravado na mesma transação do efeito).
CREATE TABLE processed_message (
	message_id VARCHAR(36) NOT NULL PRIMARY KEY,
	type VARCHAR(64) NOT NULL,
	saga_id VARCHAR(36) NOT NULL,
	processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
