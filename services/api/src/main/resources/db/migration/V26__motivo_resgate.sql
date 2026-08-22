-- =============================================================================
-- V26 — RESGATE no CHECK de lancamento.motivo
--
-- O motivo que QUEIMA token. Fecha o par que o ADR 0027 descreve:
--
--   APORTE_PATROCINADOR  credita sem debitar contraparte  -> EMITE   (V23)
--   RESGATE              debita  sem creditar contraparte -> QUEIMA  (esta)
--
-- Todos os outros motivos movem token de um lugar para outro e somam zero.
--
-- CONSEQUÊNCIA DE ENUNCIADO, e ela é o ponto desta fase: a invariante
--
--   SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)
--
-- deixa de ser constante no SISTEMA. Ela é constante dentro do CICLO DE MISSÕES, e
-- muda nas duas pontas — sobe no aporte, desce no resgate. Isso não é regressão: é
-- o que transforma a economia de estoque fechado em ciclo com entrada e saída. A
-- RECONCILIAÇÃO (ledger × projeção) continua valendo intocada, porque a queima
-- escreve os dois lados como qualquer outro lançamento.
-- =============================================================================

ALTER TABLE lancamento DROP CONSTRAINT ck_lancamento_motivo;

ALTER TABLE lancamento ADD CONSTRAINT ck_lancamento_motivo CHECK (motivo IN (
    'RECOMPENSA_MISSAO', 'TRANSFERENCIA_ENVIADA', 'TRANSFERENCIA_RECEBIDA',
    'FINANCIAMENTO_TRIBO', 'FINANCIAMENTO_PATROCINADOR', 'APORTE_PATROCINADOR',
    'RESGATE', 'SAQUE', 'BONUS', 'ESTORNO'
));

COMMENT ON CONSTRAINT ck_lancamento_motivo ON lancamento IS
  'Dois motivos não somam zero, e são as duas pontas do ciclo: APORTE_PATROCINADOR EMITE token '
  '(credita sem contraparte) e RESGATE QUEIMA (debita sem contraparte). Acrescentar um terceiro que '
  'não some zero exige decidir de que lado do ciclo ele fica — ver ADR 0027 e o javadoc de '
  'MotivoLancamento.BONUS.';
