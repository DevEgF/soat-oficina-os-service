-- Compensações da saga: motivo do cancelamento da OS, motivo da falha e pendências da saga.

-- Tipo do gatilho do cancelamento (QuoteGenerationFailed, QuoteRejected, PaymentRejected,
-- ExecutionFailed ou ApprovalTimeout); nulo enquanto a OS não for cancelada.
ALTER TABLE ordens_servico ADD COLUMN motivo_cancelamento VARCHAR(64);

-- Verificação periódica do prazo de aprovação: OS em PENDING_APPROVAL pela data de entrada no status.
CREATE INDEX idx_os_status_orcamento_gerado_em ON ordens_servico (status, orcamento_gerado_em);

-- Texto do motivo informado pelo participante que falhou.
ALTER TABLE saga_os ADD COLUMN motivo_falha VARCHAR(500);

-- Compensações pedidas e ainda não confirmadas pelo Billing (QuoteCancelled / PaymentRefunded).
ALTER TABLE saga_os ADD COLUMN pendente_cancelar_orcamento BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE saga_os ADD COLUMN pendente_estornar_pagamento BOOLEAN NOT NULL DEFAULT FALSE;
