-- =============================================================================
-- V907 — As entregas abertas do seed passam a ser patrocinadas (ADR 0038).
--
-- As missões ENTREGA da V900 e da V903 nasceram de entregas falidas
-- (V901 registra a conversão), mas foram semeadas antes da carteira de
-- patrocinador existir: entram como CUNHAGEM, sem pote, e a conclusão delas
-- EMITIRIA token. Com a V31 nenhuma missão nova cunha; deixar as do seed
-- cunhando faria toda demonstração exercitar justamente o caminho que saiu.
--
-- Esta seed dá a elas o que a conversão do webhook dá hoje: o patrocinador de
-- `transportadora-dev` (V905) financia o pote, com o lançamento
-- FINANCIAMENTO_PATROCINADOR correspondente — o mesmo que
-- FinanciamentoCarteiraService grava. Só as NÃO-TERMINAIS: as concluídas já
-- pagaram, e o ledger registrou como.
--
-- Ledger e projeção ficam coerentes por construção: saldo_apos_tokens é o saldo
-- corrente descontado do acumulado, na mesma ordem dos INSERTs, e a carteira é
-- debitada do total. MigracaoTest confere a soma do ledger contra o saldo de
-- toda carteira semeada.
-- =============================================================================

CREATE TEMP TABLE v907_alvo ON COMMIT DROP AS
SELECT id,
       tokens_recompensa,
       SUM(tokens_recompensa) OVER (ORDER BY id) AS acumulado
  FROM missao
 WHERE fonte_pote = 'CUNHAGEM'
   AND status NOT IN ('CONCLUIDA', 'CANCELADA', 'EXPIRADA')
   AND tokens_recompensa > 0;

INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                        missao_id, contraparte_carteira_id, chave_idempotencia,
                        saldo_apos_brl, saldo_apos_tokens, criado_em)
SELECT gen_random_uuid(),
       c.id,
       'DEBITO',
       'FINANCIAMENTO_PATROCINADOR',
       0.00,
       a.tokens_recompensa,
       a.id,
       NULL,
       'seed-v907-patrocinio-' || a.id,
       c.saldo_brl,
       c.saldo_tokens - a.acumulado,
       NOW()
  FROM v907_alvo a
 CROSS JOIN carteira c
 WHERE c.id = 'eeeeeeee-0000-0000-0000-000000000950';

UPDATE carteira
   SET saldo_tokens = saldo_tokens - (SELECT COALESCE(SUM(tokens_recompensa), 0) FROM v907_alvo),
       versao = versao + 1
 WHERE id = 'eeeeeeee-0000-0000-0000-000000000950';

UPDATE missao m
   SET fonte_pote  = 'PATROCINADOR',
       pote_tokens = a.tokens_recompensa
  FROM v907_alvo a
 WHERE m.id = a.id;
