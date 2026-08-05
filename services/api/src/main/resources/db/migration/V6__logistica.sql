-- =============================================================================
-- V6 — Módulo logistica
-- Tabelas: ponto_custodia, entrega_falida
-- =============================================================================

-- Entidade de primeira classe: ponto onde encomendas de entregas falidas ficam
-- guardadas até que alguém aceite a missão de retirá-las e entregar ao destinatário.
-- Tipos: LOJA (balcão de retirada), LOCKER (armário automático),
--        PORTARIA (condomínio), VIZINHO (pessoa de confiança da tribo).
CREATE TABLE ponto_custodia (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo      VARCHAR(20) NOT NULL,
    tipo        VARCHAR(10) NOT NULL CHECK (tipo IN ('LOJA', 'LOCKER', 'PORTARIA', 'VIZINHO')),
    apelido     VARCHAR(100) NOT NULL,
    ponto       GEOGRAPHY(POINT,4326) NOT NULL,
    -- tribo_id: FK para tribo — módulo→identidade é permitido
    tribo_id    UUID        REFERENCES tribo(id),
    capacidade  INT         NOT NULL,
    ocupacao    INT         NOT NULL DEFAULT 0,
    ativo       BOOLEAN     NOT NULL DEFAULT TRUE,
    criado_em   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_ponto_custodia_codigo UNIQUE (codigo)
);

-- Registro de entregas que falharam e foram depositadas num ponto de custódia.
-- missao_id: UUID puro, sem FK — fronteira de módulo logistica→missoes.
-- Deliberado: mantém o módulo logistica desacoplado do ciclo de vida de missoes.
CREATE TABLE entrega_falida (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    transportadora        VARCHAR(100) NOT NULL,
    codigo_rastreio       VARCHAR(100) NOT NULL,
    motivo                VARCHAR(500) NOT NULL,
    -- ponto_custodia_id: FK dentro do mesmo módulo, permitida
    ponto_custodia_id     UUID        NOT NULL REFERENCES ponto_custodia(id),
    missao_id             UUID,        -- UUID puro: sem FK (ver comentário acima)
    recebido_em           TIMESTAMPTZ NOT NULL,
    assinatura_verificada BOOLEAN     NOT NULL DEFAULT FALSE,
    convertida_em         TIMESTAMPTZ             -- preenchido quando vira missão de retirada
);

GRANT SELECT, INSERT, UPDATE, DELETE ON ponto_custodia TO omnitribo_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON entrega_falida TO omnitribo_app;
