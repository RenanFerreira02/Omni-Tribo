-- =============================================================================
-- V30 — FontePote.SEM_TOKEN: missão comunitária que recompensa só reputação.
--
-- Contexto (ADR 0035). O ADR 0025 trouxe AJUDA para COMUNIDADE e, com isso,
-- publicar uma AJUDA passou a exigir pote financiado por outro membro da tribo.
-- O próprio ADR previu o custo, em "Negativas": "AJUDA deixa de ir ao ar na
-- hora [...] se financiar favor alheio se mostrar pouco atraente na prática,
-- AJUDA fica represada em RASCUNHO." Foi exatamente o que aconteceu ao usar o
-- app.
--
-- A saída NÃO é afrouxar a conservação. É representar na economia a missão que
-- a tese do produto sempre teve e o modelo nunca escreveu: mutirão e favor de
-- vizinho que valem REPUTAÇÃO, não moeda. Uma missão SEM_TOKEN tem
-- tokens_recompensa = 0 e pote_tokens = 0; ela não emite token na conclusão nem
-- queima nenhum, então não é uma quarta ponta da invariante de conservação —
-- ela simplesmente não participa dela.
--
-- Por que um valor novo, e não reusar COMUNIDADE com tokens_recompensa = 0:
-- COMUNIDADE afirma "pote financiado por membros da tribo", e nessas missões
-- esse pote nunca existe. Com a fonte errada, FinanciamentoService.validarEstado
-- deixaria o financiamento passar e a recusa sairia lá de validarTeto como
-- aritmética sem sentido ("pote ficaria com 10, acima da recompensa de 0.
-- Faltam apenas 0."). É o mesmo argumento do §3 do ADR 0024, que criou esta
-- coluna: regra que depende da fonte se lê da FONTE, nunca de uma lista de
-- categorias — e as duas TRIBOs, a financiada e a de só XP, não se distinguem
-- por categoria.
--
-- SEM_TOKEN tem 9 caracteres e a coluna é VARCHAR(12): não há ALTER de tipo.
--
-- NÃO há backfill, e a ausência é deliberada — mesma razão que o ADR 0025 deu
-- para não converter as AJUDAs antigas: nenhuma missão existente foi criada
-- como só-XP, e reclassificar linha histórica reescreveria o passado para
-- explicá-la por uma regra que não a produziu.
--
-- Continua NÃO existindo CHECK de coerência entre fonte_pote e categoria (ver
-- V23, seção 4): ele reprovaria os INSERTs dos seeds 900+, que rodam depois
-- desta migration. A coerência mora no construtor de Missao, que é ponto único.
-- =============================================================================

ALTER TABLE missao DROP CONSTRAINT ck_missao_fonte_pote;

ALTER TABLE missao ADD CONSTRAINT ck_missao_fonte_pote
    CHECK (fonte_pote IN ('COMUNIDADE', 'PATROCINADOR', 'CUNHAGEM', 'SEM_TOKEN'));

-- O comentário da V23 ficou desatualizado em 2026-08-21, quando o ADR 0025
-- tirou AJUDA de CUNHAGEM: ele ainda afirmava "só AJUDA e ENTREGA criada por
-- humano". Reescrito aqui inteiro, em vez de emendado, para que o catálogo do
-- banco pare de contradizer o construtor de Missao.
COMMENT ON COLUMN missao.fonte_pote IS
  'De onde sai o token da recompensa, congelada na criação: COMUNIDADE (pote financiado por '
  'membros da tribo — TRIBO, COLETA e AJUDA), PATROCINADOR (pote financiado pelo patrocinador da '
  'transportadora, na conversão de entrega falida), CUNHAGEM (emitido na conclusão, sem '
  'financiador — só ENTREGA criada por humano) ou SEM_TOKEN (recompensa só em XP; '
  'tokens_recompensa = 0, não aceita financiamento e não move token nenhum).';
