-- =============================================================================
-- V17 — Código legível por máquina do motivo de rejeição do check-in (ADR 0010)
--
-- Sem GRANT novo: privilégio no PostgreSQL é por tabela, e os GRANT/REVOKE da V4
-- sobrevivem ao ALTER TABLE. checkin continua append-only para omnitribo_app.
-- =============================================================================

-- motivo_rejeicao (V4) é texto em português para humano: "Você está a 340 m da origem da missão;
-- o raio permitido é 50 m." Serve para a análise antifraude e para a mensagem na tela, e NÃO serve
-- para o app decidir comportamento — a frase muda a cada revisão de copy.
--
-- codigo_rejeicao é o mesmo fato em forma estável. É ele que escolhe a URI do campo `type` da
-- resposta RFC 9457, e por isso PRECISA ser persistido, não apenas calculado: um check-in rejeitado
-- pode ser relido pela chave de idempotência (uk_checkin_idempotencia, V12), e nesse replay o
-- veredito é reconstruído a partir DESTA linha. Sem a coluna, a primeira tentativa responderia
-- `checkin-fora-do-raio` e o replay da mesma chave responderia o tipo genérico — dois contratos
-- para a mesma operação.
ALTER TABLE checkin ADD COLUMN codigo_rejeicao VARCHAR(40);

-- varchar + CHECK, nunca ENUM nativo nem ordinal (convenção do projeto).
-- Os valores espelham geolocalizacao/api/MotivoRejeicaoCheckin.
ALTER TABLE checkin ADD CONSTRAINT ck_checkin_codigo_rejeicao
    CHECK (codigo_rejeicao IS NULL OR codigo_rejeicao IN (
        'LOCALIZACAO_SIMULADA',
        'ACURACIA_INSUFICIENTE',
        'FORA_DO_RAIO'
    ));

-- Coerência com valido: rejeitado tem código, aceito não tem. Sem esta constraint o banco
-- admitiria uma rejeição sem código — exatamente a linha que faria o replay cair no tipo genérico
-- e reabrir o problema que a coluna existe para fechar.
--
-- Aplicável sem backfill: não há linha de checkin no seed (V900/V901), e toda base de dev é
-- reconstruída por `make reset` a cada migration nova por causa da faixa 900.
ALTER TABLE checkin ADD CONSTRAINT ck_checkin_rejeicao_coerente
    CHECK ((valido = TRUE AND codigo_rejeicao IS NULL)
        OR (valido = FALSE AND codigo_rejeicao IS NOT NULL));

COMMENT ON COLUMN checkin.codigo_rejeicao IS
    'Motivo da rejeição em forma estável (ADR 0010). Discrimina o `type` do ProblemDetail; '
    'motivo_rejeicao é o texto para humano da mesma decisão.';
