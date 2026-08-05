-- =============================================================================
-- V4 — Módulo geolocalizacao
-- Tabelas: checkin
-- =============================================================================

-- APPEND-ONLY — registra todos os check-ins, incluindo os rejeitados.
-- Rejeições ficam gravadas (valido=false + motivo_rejeicao) para análise de
-- tentativas de fraude (GPS simulado, velocidade implausível, QR fora do raio).
-- missao_id é UUID puro, sem FK — fronteira de módulo: geolocalizacao→missoes.
-- A ausência de FK viabiliza extração futura do módulo sem dependência de schema.
CREATE TABLE checkin (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    missao_id               UUID        NOT NULL,   -- UUID puro: sem FK (ver comentário acima)
    -- usuario_id: FK para usuario — módulo→identidade é permitido
    usuario_id              UUID        NOT NULL REFERENCES usuario(id),
    ponto                   GEOGRAPHY(POINT,4326) NOT NULL,
    acuracia_m              NUMERIC(10,2) NOT NULL,
    distancia_alvo_m        NUMERIC(10,2) NOT NULL,
    metodo                  VARCHAR(5)  NOT NULL CHECK (metodo IN ('GPS', 'QR')),
    mock_detectado          BOOLEAN     NOT NULL DEFAULT FALSE,
    -- velocidade calculada entre check-ins sucessivos; null no primeiro check-in
    velocidade_implicita_kmh NUMERIC(10,2),
    valido                  BOOLEAN     NOT NULL,
    motivo_rejeicao         VARCHAR(500),           -- null quando valido=true
    criado_em               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- checkin é APPEND-ONLY: histórico de localização imutável (evidência de execução)
GRANT  SELECT, INSERT ON checkin TO omnitribo_app;
REVOKE UPDATE, DELETE ON checkin FROM omnitribo_app;
