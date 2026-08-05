-- =============================================================================
-- V8 — Índices de performance e permissões finais
-- =============================================================================

-- GiST: obrigatórios para consultas geoespaciais eficientes (ST_DWithin, ST_Distance).
-- Sem estes índices, qualquer busca por raio faz seq scan na tabela inteira.
CREATE INDEX idx_missao_origem          ON missao         USING GIST (origem);
CREATE INDEX idx_ponto_custodia_ponto   ON ponto_custodia USING GIST (ponto);
CREATE INDEX idx_checkin_ponto          ON checkin        USING GIST (ponto);

-- B-tree: queries frequentes de aplicação
CREATE INDEX idx_missao_status          ON missao        (status);
CREATE INDEX idx_missao_categoria       ON missao        (categoria);
-- Partial index: executor_id é null nas missões sem executor (DISPONIVEL/CRIADA)
CREATE INDEX idx_missao_executor        ON missao        (executor_id)  WHERE executor_id IS NOT NULL;
CREATE INDEX idx_missao_criador         ON missao        (criador_id);
CREATE INDEX idx_missao_janela          ON missao        (janela_inicio, janela_fim);

CREATE INDEX idx_lancamento_carteira    ON lancamento    (carteira_id);
CREATE INDEX idx_lancamento_missao      ON lancamento    (missao_id)    WHERE missao_id IS NOT NULL;
CREATE INDEX idx_lancamento_criado      ON lancamento    (criado_em);

CREATE INDEX idx_refresh_token_hash     ON refresh_token (token_hash);
CREATE INDEX idx_refresh_token_familia  ON refresh_token (familia_id);
CREATE INDEX idx_refresh_token_usuario  ON refresh_token (usuario_id);

CREATE INDEX idx_checkin_missao         ON checkin       (missao_id);
CREATE INDEX idx_checkin_usuario        ON checkin       (usuario_id);

CREATE INDEX idx_missao_evento_missao   ON missao_evento (missao_id);
CREATE INDEX idx_missao_evento_criado   ON missao_evento (criado_em);

CREATE INDEX idx_auditoria_ator         ON auditoria     (ator_id)      WHERE ator_id IS NOT NULL;
CREATE INDEX idx_auditoria_criado       ON auditoria     (criado_em);
CREATE INDEX idx_auditoria_entidade     ON auditoria     (entidade, entidade_id);

CREATE INDEX idx_alerta_usuario         ON alerta        (usuario_id)   WHERE usuario_id IS NOT NULL;
CREATE INDEX idx_alerta_nao_lido        ON alerta        (usuario_id, lido) WHERE lido = FALSE;

CREATE INDEX idx_outbox_nao_publicado   ON outbox        (criado_em)    WHERE publicado_em IS NULL;

-- Garante que omnitribo_app tem permissão de uso do schema público
GRANT USAGE ON SCHEMA public TO omnitribo_app;
