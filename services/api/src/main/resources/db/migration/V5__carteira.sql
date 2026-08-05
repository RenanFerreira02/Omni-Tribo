-- =============================================================================
-- V5 — Módulo carteira
-- Tabelas: carteira, lancamento
-- =============================================================================

CREATE TABLE carteira (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- usuario_id: FK para usuario — módulo→identidade é permitido
    usuario_id     UUID         NOT NULL REFERENCES usuario(id),
    -- numeric(12,2) → BigDecimal; nunca double (erros de ponto flutuante em BRL)
    saldo_brl      NUMERIC(12,2) NOT NULL DEFAULT 0.00,
    saldo_tokens   BIGINT       NOT NULL DEFAULT 0,
    versao         INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_carteira_usuario UNIQUE (usuario_id)
);

-- APPEND-ONLY — ledger financeiro: cada linha é imutável após INSERT.
-- Correções são feitas por ESTORNO (nova linha com sinal oposto), nunca por UPDATE.
-- Isso garante auditabilidade completa e previne manipulação retroativa de saldos.
--
-- missao_id: UUID puro, SEM FK e SEM @ManyToOne.
-- Deliberado: isola o módulo carteira do módulo missoes para viabilizar extração
-- futura em microsserviço independente sem quebrar o schema financeiro.
CREATE TABLE lancamento (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    carteira_id             UUID        NOT NULL REFERENCES carteira(id),
    sinal                   VARCHAR(7)  NOT NULL CHECK (sinal IN ('CREDITO', 'DEBITO')),
    motivo                  VARCHAR(30) NOT NULL CHECK (motivo IN (
                                'RECOMPENSA_MISSAO', 'TRANSFERENCIA_ENVIADA',
                                'TRANSFERENCIA_RECEBIDA', 'FINANCIAMENTO_TRIBO',
                                'SAQUE', 'BONUS', 'ESTORNO'
                            )),
    valor_brl               NUMERIC(12,2) NOT NULL DEFAULT 0.00,
    valor_tokens            BIGINT       NOT NULL DEFAULT 0,
    missao_id               UUID,        -- UUID puro: sem FK (ver comentário acima)
    -- contraparte dentro do mesmo módulo; FK permitida
    contraparte_carteira_id UUID        REFERENCES carteira(id),
    -- Garantia de idempotência a nível de banco: segunda chamada com a mesma chave falha
    chave_idempotencia      VARCHAR(100) NOT NULL,
    saldo_apos_brl          NUMERIC(12,2) NOT NULL,
    saldo_apos_tokens       BIGINT       NOT NULL,
    criado_em               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_lancamento_idempotencia UNIQUE (chave_idempotencia)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON carteira TO omnitribo_app;

-- lancamento é APPEND-ONLY: ledger financeiro imutável, correção por ESTORNO
GRANT  SELECT, INSERT ON lancamento TO omnitribo_app;
REVOKE UPDATE, DELETE ON lancamento FROM omnitribo_app;
