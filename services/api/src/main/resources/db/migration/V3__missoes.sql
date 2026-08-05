-- =============================================================================
-- V3 — Módulo missoes
-- Tabelas: missao, missao_evento
-- =============================================================================

CREATE TABLE missao (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- criador_id/executor_id: FK para usuario (módulo→identidade é permitido)
    criador_id        UUID         NOT NULL REFERENCES usuario(id),
    executor_id       UUID         REFERENCES usuario(id),
    categoria         VARCHAR(10)  NOT NULL CHECK (categoria IN ('ENTREGA', 'COLETA', 'TRIBO', 'AJUDA')),
    titulo            VARCHAR(200) NOT NULL,
    descricao         TEXT         NOT NULL,
    status            VARCHAR(15)  NOT NULL CHECK (status IN (
                          'CRIADA', 'DISPONIVEL', 'ACEITA', 'EM_ANDAMENTO',
                          'CONCLUIDA', 'CANCELADA', 'EXPIRADA'
                      )),
    xp_recompensa     INT          NOT NULL DEFAULT 0,
    -- numeric(12,2) → BigDecimal: dinheiro real, nunca double
    valor_brl         NUMERIC(12,2) NOT NULL DEFAULT 0.00,
    tokens_recompensa BIGINT       NOT NULL DEFAULT 0,
    origem            GEOGRAPHY(POINT,4326) NOT NULL,
    destino           GEOGRAPHY(POINT,4326),
    -- ponto_custodia_id: UUID puro, sem FK — referência cruzada missoes→logistica.
    -- Deliberado: V6 (logistica) vem depois desta migration; além disso, a ausência
    -- de FK viabiliza a extração futura do módulo logistica sem quebrar este schema.
    ponto_custodia_id UUID,
    cep               VARCHAR(8)   NOT NULL,
    logradouro        VARCHAR(200) NOT NULL,
    bairro            VARCHAR(100) NOT NULL,
    cidade            VARCHAR(100) NOT NULL,
    uf                VARCHAR(2)   NOT NULL,
    raio_checkin_m    INT          NOT NULL DEFAULT 50,
    -- peso e volume informam recompensa e instruções de manuseio para itens volumosos
    peso_kg           NUMERIC(6,2),
    volume_l          NUMERIC(8,2),
    janela_inicio     TIMESTAMPTZ  NOT NULL,
    janela_fim        TIMESTAMPTZ  NOT NULL,
    criada_em         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    aceita_em         TIMESTAMPTZ,
    concluida_em      TIMESTAMPTZ,
    versao            INT          NOT NULL DEFAULT 0,
    -- Invariante econômica: missões comunitárias (TRIBO/COLETA) não remuneram em BRL
    CONSTRAINT ck_missao_economia CHECK (
        categoria NOT IN ('TRIBO', 'COLETA') OR valor_brl = 0
    )
);

-- APPEND-ONLY — trilha completa do ciclo de vida da missão.
-- Cada transição de status gera um novo evento; nunca se apaga ou edita o histórico.
CREATE TABLE missao_evento (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    missao_id   UUID        NOT NULL REFERENCES missao(id),
    tipo        VARCHAR(30) NOT NULL CHECK (tipo IN (
                    'PUBLICADA', 'ACEITA', 'CHECK_IN_REGISTRADO',
                    'CONCLUIDA', 'CANCELADA', 'EXPIRADA', 'CHECK_IN_REJEITADO'
                )),
    -- ator_id: FK para usuario (cross-module para identidade é permitido)
    ator_id     UUID        REFERENCES usuario(id),
    de_status   VARCHAR(15),
    para_status VARCHAR(15),
    payload     JSONB,
    criado_em   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON missao TO omnitribo_app;

-- missao_evento é APPEND-ONLY: histórico do ciclo de vida não pode ser adulterado
GRANT  SELECT, INSERT ON missao_evento TO omnitribo_app;
REVOKE UPDATE, DELETE ON missao_evento FROM omnitribo_app;
