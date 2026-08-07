-- =============================================================================
-- V12 — Check-in idempotente e marcação de suspeita (F6)
--
-- Sem GRANT novo: privilégio no PostgreSQL é por tabela, e os GRANT/REVOKE da V4
-- sobrevivem ao ALTER TABLE. checkin continua append-only para omnitribo_app.
-- =============================================================================

-- Guarda sha256(usuario_id|missao_id|chave_do_cliente), NUNCA a chave crua.
--
-- A UNIQUE é de coluna única e namespace global — mesma forma de uk_lancamento_idempotencia (V5).
-- Com a chave crua, o cliente que envia "1" colidiria com todo outro cliente que envia "1", e a
-- segunda chamada receberia como resposta o REPLAY DO CHECK-IN ALHEIO. O hash composto torna a
-- colisão entre usuários impossível por construção, sem precisar de UNIQUE composta.
--
-- Nullable de propósito: o caminho QR (MetodoCheckin.QR, já previsto na V4) pode não ter chave
-- vinda do cliente, e o PostgreSQL admite múltiplos NULL sob UNIQUE.
ALTER TABLE checkin ADD COLUMN chave_idempotencia VARCHAR(100);

-- Cinemática implausível NÃO rejeita: marca. valido continua true, a missão transiciona
-- normalmente, e a suspeita alimenta a análise de fraude e a confirmação humana da F7.
-- Coluna própria em vez de derivar de velocidade_implicita_kmh > 120: o limiar é regra de
-- aplicação e mudaria o significado retroativo de toda linha já gravada.
ALTER TABLE checkin ADD COLUMN suspeito BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE checkin ADD CONSTRAINT uk_checkin_idempotencia UNIQUE (chave_idempotencia);

-- A checagem de plausibilidade lê o ÚLTIMO check-in do usuário. idx_checkin_usuario (V8)
-- resolve o filtro mas deixaria o ORDER BY criado_em DESC LIMIT 1 para um sort em memória.
CREATE INDEX idx_checkin_usuario_criado ON checkin (usuario_id, criado_em DESC);
