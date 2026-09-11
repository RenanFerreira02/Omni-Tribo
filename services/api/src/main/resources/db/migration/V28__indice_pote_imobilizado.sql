-- Índice parcial do diagnóstico de pote imobilizado (ADR 0032).
--
-- A consulta procura um conjunto MINÚSCULO dentro de uma tabela grande: missões não-terminais com
-- pote_tokens > 0 paradas além do limiar. Sem índice o PostgreSQL varre `missao` inteira e descarta
-- quase tudo no filtro — e uma das duas consultas roda em TODA chamada de
-- GET /admin/carteiras/reconciliacao, não só no endpoint de diagnóstico.
--
-- Medido antes de escrever esta migration, em bancada de 50.000 missões sintéticas distribuídas
-- pelos seis estados não-terminais (1 em 10 com pote, 1 em 100 parada além do limiar), com ANALYZE
-- antes e as linhas apagadas depois:
--
--   EXPLAIN (ANALYZE, BUFFERS)          sem índice        com índice
--   listagem paginada (LIMIT 20)        6,224 ms          0,481 ms     buffers 2008 → 503
--   contagem + soma (reconciliação)     5,263 ms          0,254 ms     buffers 2002 → 503
--
-- Seq Scan com 49.518 linhas removidas pelo filtro virou Index Scan. O índice ocupou 56 kB contra
-- 16 MB de tabela — 0,3% —, porque é PARCIAL: só indexa a fração que pode ser candidata.
--
-- Escolha do predicado parcial: `pote_tokens > 0` mais os três estados terminais fora. Missão sem
-- pote não tem o que imobilizar, e missão terminal teve o pote estornado ou pago — as duas
-- condições eliminam a maior parte da tabela e são IMMUTABLE, que é o que um índice parcial exige.
--
-- A expressão indexada repete a escolha de marco de RegraExpiracao.Marco: ABERTA mede pela janela
-- de OFERTA (prazo absoluto escolhido pelo criador), os demais pelo tempo parado no estado.
--
-- DEGRADAÇÃO CONHECIDA, e ela foi MEDIDA em vez de suposta. A primeira versão deste comentário
-- afirmava que com dois statuses em `medidosPorJanelaFim()` o índice "deixa de ser usado e a
-- consulta volta ao Seq Scan". Isso está ERRADO, e o EXPLAIN desmentiu:
--
--   contagem + soma, mesma bancada de 50.000 linhas        plano escolhido            tempo
--   status IN ('ABERTA')          — o caso de hoje         Index Scan (Index Cond)     0,324 ms
--   status IN ('ABERTA','ACEITA') — hipótese futura        Bitmap Index Scan           1,688 ms
--   sem este índice                                        Seq Scan                    5,462 ms
--
-- O que de fato acontece: com UM status o PostgreSQL normaliza `status IN ('ABERTA')` para
-- `status = 'ABERTA'`, a expressão casa com a indexada e o marco vira condição de RANGE dentro do
-- índice. Com dois, a expressão deixa de casar — mas o índice CONTINUA sendo usado pelo predicado
-- parcial, varrido inteiro (5.004 entradas) e filtrado depois. Fica ~5× mais lento que hoje e
-- ainda ~3× mais rápido que não ter índice nenhum.
--
-- Ou seja: acrescentar um Marco.JANELA_FIM novo é seguro e não derruba o diagnóstico — só merece
-- uma remedição, e trocar a expressão indexada se o número incomodar.
--
-- CREATE INDEX sem CONCURRENTLY de propósito: Flyway roda a migration dentro de uma transação e
-- CONCURRENTLY não pode rodar em uma. O lock de escrita sobre `missao` dura o tempo de construir um
-- índice de 56 kB.
CREATE INDEX idx_missao_pote_imobilizado
    ON missao ((CASE WHEN status = 'ABERTA' THEN janela_fim ELSE estado_desde END))
 WHERE pote_tokens > 0
   AND status NOT IN ('CONCLUIDA', 'CANCELADA', 'EXPIRADA');

COMMENT ON INDEX idx_missao_pote_imobilizado IS
  'Diagnóstico de pote imobilizado (ADR 0032): token preso em missão não-terminal parada. '
  'Serve GET /admin/missoes/potes-imobilizados e o campo potesImobilizados da reconciliação.';
