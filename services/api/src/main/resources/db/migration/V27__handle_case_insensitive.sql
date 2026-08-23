-- =============================================================================
-- V27 — Handle único IGNORANDO CAIXA
--
-- Sustenta a busca por handle exato (GET /api/v1/usuarios/busca), que fecha a
-- Pendência #3: a tela de transferência pedia o UUID do destinatário como texto.
-- Ver ADR 0028.
--
-- POR QUE V27 E NÃO V28: o schema ia até a V26 e a V27 estava livre — conferido
-- em todas as refs, inclusive remotas. Reservar a partir da V28 deixaria um
-- buraco, e buraco de versão é bomba-relógio: um V27 criado depois por qualquer
-- branch fica out-of-order para todo banco que já aplicou a V28, e
-- application-dev.yml mantém out-of-order DESLIGADO. É a mesma armadilha que
-- queimou a V9 e a V10.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- `uk_usuario_handle UNIQUE (handle)` existe desde a V2 e é CASE-SENSITIVE: hoje
-- `alice` e `Alice` podem coexistir como duas contas diferentes.
--
-- Isso quebra a busca exata antes mesmo de ela existir. O usuário digita "@alice"
-- pensando numa pessoa; com duas linhas possíveis, ou a consulta devolve duas
-- respostas para uma pergunta que tem uma só, ou devolve UMA — a que o planner
-- escolher — e transfere token para a conta errada. Num ledger append-only, isso
-- vira estorno manual.
--
-- Um índice único sobre LOWER(handle) resolve os dois lados de uma vez: torna a
-- ambiguidade IMPOSSÍVEL de existir, e é ele que a consulta de busca usa —
-- `WHERE LOWER(u.handle) = LOWER(:handle)` sem índice funcional viraria Seq Scan
-- na tabela de usuários a cada tecla de quem procura um vizinho.
--
-- SEGURO SEM BACKFILL, e isto foi MEDIDO antes de escrever, não presumido:
--
--   SELECT LOWER(handle), COUNT(*) FROM usuario GROUP BY LOWER(handle)
--    HAVING COUNT(*) > 1;   -- devolveu VAZIO com todos os seeds aplicados
--
-- Se algum dia devolver linhas num banco real, esta migration falha alto e é isso
-- que se quer: escolher qual das contas homônimas fica é decisão de gente, não de
-- uma cláusula ON CONFLICT.
-- -----------------------------------------------------------------------------
CREATE UNIQUE INDEX uk_usuario_handle_lower ON usuario (LOWER(handle));

COMMENT ON INDEX uk_usuario_handle_lower IS
  'Unicidade de handle ignorando caixa, e índice da busca exata por @ (ADR 0028). O UNIQUE '
  'case-sensitive da V2 continua: ele virou redundante, mas removê-lo mudaria o contrato de '
  'unicidade sem ganho nenhum.';
