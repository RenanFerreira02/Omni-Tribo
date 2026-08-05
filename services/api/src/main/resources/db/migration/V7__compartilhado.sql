-- =============================================================================
-- V7 — Módulo compartilhado
-- Tabelas: outbox, alerta
-- =============================================================================

-- Transactional Outbox Pattern: eventos de domínio gravados na mesma transação
-- da mutação de estado. Um processo separado (a implementar) lê e publica.
-- Desacoplado de broker de mensageria — conforme decisão de escopo do MVP.
CREATE TABLE outbox (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tipo_evento  VARCHAR(100) NOT NULL,
    agregado_id  UUID        NOT NULL,
    payload      JSONB       NOT NULL,
    criado_em    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    publicado_em TIMESTAMPTZ,
    tentativas   INT         NOT NULL DEFAULT 0
);

-- Caixa de entrada in-app do usuário. Também usado para alertas administrativos
-- (usuario_id null) e alertas vinculados a missões.
-- missao_id: UUID puro, sem FK — fronteira compartilhado→missoes.
CREATE TABLE alerta (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- usuario_id: FK para usuario — módulo→identidade é permitido; null = alerta global
    usuario_id UUID        REFERENCES usuario(id),
    tipo       VARCHAR(50) NOT NULL,
    titulo     VARCHAR(200) NOT NULL,
    corpo      TEXT        NOT NULL,
    missao_id  UUID,        -- UUID puro: sem FK (ver comentário acima)
    lido       BOOLEAN     NOT NULL DEFAULT FALSE,
    criado_em  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON outbox TO omnitribo_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON alerta TO omnitribo_app;
