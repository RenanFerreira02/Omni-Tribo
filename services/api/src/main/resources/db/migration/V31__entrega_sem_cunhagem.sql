-- =============================================================================
-- V31 — ENTREGA criada por pessoa deixa de emitir token (ADR 0038).
--
-- Até aqui a ENTREGA criada no app nascia CUNHAGEM: publicava sem pote e a
-- conclusão EMITIA a recompensa, sem contrapartida — a última emissão implícita
-- do sistema. A partir desta versão ela segue a regra de TRIBO e AJUDA: pote
-- financiado por outros membros da tribo (COMUNIDADE) ou só XP (SEM_TOKEN). A
-- derivação mora no construtor de Missao; esta migration só trata o que já
-- está no banco.
--
-- RASCUNHO legado vira COMUNIDADE: ninguém aceitou ainda, nenhuma promessa foi
-- feita, e a missão passa a exigir pote para publicar.
--
-- Missão JÁ PUBLICADA em CUNHAGEM fica como está. Ela foi aceita (ou está
-- aberta para aceite) com uma recompensa prometida; trocá-la para COMUNIDADE
-- com pote zero a tornaria impossível de concluir. Missao.recongelarRecompensa
-- preserva essa fonte legada pelo mesmo motivo. Terminais também ficam: são
-- história, e o ledger já registrou o que cada uma pagou.
--
-- O CHECK continua aceitando 'CUNHAGEM' pelos dois motivos acima.
-- =============================================================================

UPDATE missao
   SET fonte_pote = 'COMUNIDADE'
 WHERE fonte_pote = 'CUNHAGEM'
   AND status = 'RASCUNHO';

COMMENT ON COLUMN missao.fonte_pote IS
  'De onde sai o token da recompensa, congelada na criação: COMUNIDADE (pote financiado por '
  'outros membros da tribo), PATROCINADOR (pote financiado pelo patrocinador da transportadora, '
  'na conversão de entrega falida), SEM_TOKEN (recompensa só em XP; não move token) ou CUNHAGEM '
  '(LEGADO, anterior à V31: emitido na conclusão, sem financiador; nenhuma missão nova nasce assim).';
