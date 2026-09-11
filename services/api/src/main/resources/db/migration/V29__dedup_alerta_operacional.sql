-- Deduplicação do alerta operacional global, e o índice que dá leitor à frequência (ADR 0033).
--
-- O teste de carga de 2026-08-25 (docs/evidencias/f21-carga.md §6) gravou 631 linhas idênticas em
-- `alerta`, todas PONTO_CUSTODIA_LOTADO, todas do mesmo ponto, em menos de 3 minutos. O teto
-- app.notificacoes.alertas-por-hora não pega isso e está certo em não pegar: ele é POR USUÁRIO, e
-- este alerta é global de propósito (usuario_id nulo). Faltava regra para sinal de OPERAÇÃO.

-- -----------------------------------------------------------------------------
-- 1. A chave de deduplicação.
--
-- `referencia` é VARCHAR, e não UUID, porque é chave de AGRUPAMENTO e não referência navegável.
-- A distinção importa: `missao_id` é UUID porque o app navega por ele até a missão, e é por isso
-- que DespachanteAlertaService se recusa a reaproveitá-lo para guardar o ponto. Esta coluna existe
-- só para o índice do item 2 agrupar por ela.
--
-- VARCHAR também resolve um problema que UUID teria: os dois alertas globais têm referências de
-- naturezas diferentes — o ponto de custódia tem id, mas SEM_PATROCINIO colapsa de propósito três
-- causas (patrocinador inexistente, inativo e sem fundos), e na primeira NÃO EXISTE patrocinador_id
-- para gravar. Guarda-se o ponto_custodia_id em texto num caso e o slug da transportadora no outro;
-- os dois namespaces ficam separados pela coluna `tipo`, que entra na chave.
--
-- Nuláveis, e sem CHECK exigindo preenchimento. Alerta POR USUÁRIO deixa as duas nulas — é o que o
-- predicado parcial do item 2 usa para não alcançar a caixa de entrada. Um CHECK que exigisse as
-- colunas nos alertas globais reprovaria as linhas LEGADAS, que nasceram antes desta migration e
-- não têm como preenchê-las sem inventar dado.
-- -----------------------------------------------------------------------------
ALTER TABLE alerta ADD COLUMN referencia    VARCHAR(100);
ALTER TABLE alerta ADD COLUMN janela_inicio TIMESTAMPTZ;

COMMENT ON COLUMN alerta.referencia IS
  'Chave de agrupamento do alerta operacional global: ponto_custodia_id em texto para '
  'PONTO_CUSTODIA_LOTADO, slug da transportadora para ENTREGA_SEM_PATROCINIO. NULL em alerta de '
  'usuário. Não é referência navegável — para navegar existe missao_id.';

COMMENT ON COLUMN alerta.janela_inicio IS
  'Início da janela de deduplicação, truncado em Java a partir de '
  'app.notificacoes.janela-alerta-operacional. NULL em alerta de usuário.';

-- -----------------------------------------------------------------------------
-- 2. O índice que deduplica — e o que ele NÃO garante.
--
-- Três condições no predicado, e a terceira e a quarta não são redundantes com as colunas da
-- chave. Em PostgreSQL o UNIQUE é NULLS DISTINCT por padrão: duas linhas (tipo, NULL, NULL) NÃO
-- conflitam. Sem excluir explicitamente as linhas sem chave, o índice cobriria as legadas sem
-- deduplicar nenhuma delas, e a leitura do `CREATE INDEX` sugeriria uma garantia que ele não dá.
--
-- Com o predicado explícito, o índice NASCE sobre banco que já rodou a rajada. Medido antes de
-- escrever esta migration, sobre 631 linhas legadas globais sem chave de dedup:
--
--   CREATE UNIQUE INDEX ... WHERE usuario_id IS NULL
--                             AND referencia IS NOT NULL
--                             AND janela_inicio IS NOT NULL       -> CREATE INDEX (sem erro)
--   rajada nova de 631 inserts na mesma janela                    -> INSERT 0 1
--   as 631 linhas legadas                                         -> intactas
--
-- A alternativa NULLS NOT DISTINCT foi descartada por isso: ela faria as legadas colidirem entre si
-- e o CREATE INDEX falharia justamente na máquina onde a rajada foi medida.
--
-- O QUE ISTO NÃO GARANTE, e precisa ser dito porque a leitura do índice sugere o contrário: ele não
-- obriga ninguém a preencher as colunas. Um caminho de escrita novo que grave alerta global com
-- `referencia` nula fica FORA do predicado e não é deduplicado — sem erro, sem aviso. Quem cobre
-- isso é DespachanteAlertaPontoLotadoTest, não o banco.
-- -----------------------------------------------------------------------------
CREATE UNIQUE INDEX uk_alerta_operacional
    ON alerta (tipo, referencia, janela_inicio)
 WHERE usuario_id IS NULL
   AND referencia IS NOT NULL
   AND janela_inicio IS NOT NULL;

COMMENT ON INDEX uk_alerta_operacional IS
  'Deduplicação do alerta operacional global (ADR 0033): uma linha por (tipo, referência, janela). '
  'A escrita usa INSERT ... ON CONFLICT DO NOTHING repetindo este predicado inteiro — sem repeti-lo '
  'o PostgreSQL responde "there is no unique or exclusion constraint matching the ON CONFLICT '
  'specification".';

-- -----------------------------------------------------------------------------
-- 3. O índice das recusas — o que devolve a FREQUÊNCIA que a dedup deixa de guardar.
--
-- Deduplicar o alerta apaga o número de recusas, e o número é justamente o dado que o javadoc de
-- gravarPontoLotado promete: "um ponto que recusa encomendas com frequência é exatamente o dado que
-- justifica negociar mais capacidade". Ele não se perde porque nunca esteve só no alerta —
-- entrega_falida grava TODA recusa, com ponto_custodia_id (V6), recusada_em (V21) e motivo_recusa
-- (V23). O agregado é derivável, e por isso NÃO existe tabela de agregação aqui: é o mesmo
-- argumento do ADR 0029 para o painel de impacto.
--
-- O que faltava era plano. idx_entrega_falida_ponto (V21) é parcial WHERE recusada_em IS NULL — o
-- complemento EXATO desta consulta, portanto inútil para ela. Medido em bancada de 50.000 linhas
-- (10% recusadas, 40 pontos, 144 recusas na janela de 24 h), com ANALYZE antes de cada plano:
--
--   EXPLAIN (ANALYZE, BUFFERS)        sem este índice          com este índice
--   agregado por (ponto, motivo)      2,233 ms                 0,064 ms
--   plano                             Seq Scan                 Index Scan
--   linhas descartadas no filtro      49.856                   0
--   buffers                           582                      19
--
-- Também troca GroupAggregate+Sort por HashAggregate, porque o Index Scan já entrega pouca linha.
-- O índice ocupou 128 kB contra 4.656 kB de tabela, por ser PARCIAL: indexa só o que foi recusado,
-- que é a minoria e é tudo o que a consulta lê.
--
-- DESC porque a leitura natural é "as recusas mais recentes", e a consulta filtra por
-- recusada_em >= agora - janela.
--
-- CREATE INDEX sem CONCURRENTLY pelo mesmo motivo da V28: Flyway roda a migration numa transação e
-- CONCURRENTLY não pode rodar dentro de uma.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_entrega_falida_recusa
    ON entrega_falida (recusada_em DESC)
 WHERE recusada_em IS NOT NULL;

COMMENT ON INDEX idx_entrega_falida_recusa IS
  'Frequência de recusa por ponto e motivo (ADR 0033). Serve GET /admin/pontos-custodia/recusas, '
  'que agrega na hora — sem tabela de agregação e sem cache, como o painel de impacto (ADR 0029). '
  'Complementa idx_entrega_falida_ponto, que é parcial no predicado oposto.';
