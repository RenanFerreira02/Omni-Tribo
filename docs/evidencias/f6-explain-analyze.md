# F6 — Prova de uso do índice GiST na busca por proximidade

Gerado por `IndiceGeoespacialTest` (`services/api/src/test/java/com/omnitribo/compartilhado/infra/`),
em 2026-08-07, contra PostgreSQL 16 + PostGIS 3.5 (imagem `postgis/postgis:16-3.5` via Testcontainers).

O índice provado é `idx_missao_origem ON missao USING GIST (origem)`, criado na V8.

---

## O que estava em jogo

A afirmação a provar não é "existe um índice GiST" — isso se lê no `\di`. É que **o planner escolhe
esse índice** ao executar a consulta que vai para produção. As duas formas fáceis de produzir uma
prova que não prova nada foram recusadas explicitamente:

**Recusa 1 — rodar EXPLAIN sobre a tabela como ela está.** O seed tem 12 missões. Em 12 linhas o
PostgreSQL faz *seq scan*, e faz certo: ler a tabela inteira é mais barato que descer um índice.
Um plano assim diria apenas que a tabela é pequena.

**Recusa 2 — `SET enable_seqscan = off`.** Isso força a mão do planner. Provaria que o índice *pode*
ser usado, que é uma pergunta que ninguém tem. A pergunta é se ele *é* usado quando o planner está
livre para decidir. Nenhum `enable_*` foi tocado neste teste.

O que foi feito: **200 000 missões sintéticas** espalhadas por um retângulo cobrindo o Brasil
(lon −73..−34, lat −33..−5), seguidas de **`ANALYZE missao`**, e então a consulta de produção
*verbatim*.

Três detalhes do arranjo são carga estrutural, não conveniência:

- **Todas as linhas sintéticas nascem `ABERTA`.** Isso torna `idx_missao_status` inútil — quase toda
  linha casa com o filtro — e deixa `idx_missao_origem` como único caminho seletivo. Com status
  misturado o planner poderia legitimamente preferir o B-tree, e a prova seria sobre o índice errado.
- **As coordenadas são espalhadas, não concentradas.** Carga concentrada num bairro tornaria o raio
  de 2 km pouco seletivo, e aí o seq scan voltaria a ser a escolha correta.
- **`ANALYZE` é obrigatório.** O PostGIS deriva a seletividade de `ST_DWithin` das estatísticas que
  só o `ANALYZE` coleta. Sem ele o planner recorre a um palpite fixo, e o plano deixa de ser
  evidência de qualquer coisa. (`ANALYZE` é legal dentro de bloco de transação — `VACUUM` não é — e
  o que ele grava em `pg_statistic` volta atrás junto com o rollback.)

O SQL executado é a constante `ConsultasGeoespaciais.SQL_MISSOES_NO_RAIO`, referenciada pelo teste,
não uma cópia. Se a consulta de produção mudar e deixar de usar o índice, o teste quebra.

---

## Consulta

```sql
SELECT m.id AS id,
       ST_Distance(m.origem,
           ST_SetSRID(ST_MakePoint(CAST(:lon AS double precision),
                                   CAST(:lat AS double precision)), 4326)::geography) AS distancia_m
  FROM missao m
 WHERE ST_DWithin(m.origem,
           ST_SetSRID(ST_MakePoint(CAST(:lon AS double precision),
                                   CAST(:lat AS double precision)), 4326)::geography,
           CAST(:raio AS double precision))
   AND m.status = CAST(:status AS varchar)
   AND (CAST(:categoria AS varchar) IS NULL OR m.categoria = CAST(:categoria AS varchar))
 ORDER BY distancia_m ASC
 LIMIT CAST(:limite AS integer)
```

Parâmetros: origem `(-23.5629, -46.6996)`, raio `2000` m (o default do endpoint), status `ABERTA`,
categoria `NULL`, limite `50`. Todos bindados.

---

## Saída real — `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`

Campos de ruído (`Parallel Aware`, `Async Capable`, contadores `Local`/`Temp` zerados) removidos;
nada mais foi editado.

```json
[
  {
    "Plan": {
      "Node Type": "Limit",
      "Startup Cost": 271.36,
      "Total Cost": 271.41,
      "Plan Rows": 20,
      "Actual Total Time": 21.236,
      "Actual Rows": 5,
      "Shared Hit Blocks": 60,
      "Shared Read Blocks": 7,
      "Plans": [
        {
          "Node Type": "Sort",
          "Startup Cost": 271.36,
          "Total Cost": 271.41,
          "Actual Total Time": 21.234,
          "Actual Rows": 5,
          "Sort Key": ["(st_distance(origem, '0101000020E61000000B24287E8C5947C01CEBE2361A9037C0'::geography, true))"],
          "Sort Method": "quicksort",
          "Sort Space Used": 25,
          "Sort Space Type": "Memory",
          "Plans": [
            {
              "Node Type": "Index Scan",
              "Scan Direction": "Forward",
              "Index Name": "idx_missao_origem",
              "Relation Name": "missao",
              "Alias": "m",
              "Startup Cost": 0.41,
              "Total Cost": 270.93,
              "Plan Rows": 20,
              "Actual Startup Time": 21.201,
              "Actual Total Time": 21.216,
              "Actual Rows": 5,
              "Index Cond": "(origem && _st_expand('0101000020E61000000B24287E8C5947C01CEBE2361A9037C0'::geography, '2000'::double precision))",
              "Rows Removed by Index Recheck": 0,
              "Filter": "(((status)::text = 'ABERTA'::text) AND st_dwithin(origem, '0101000020E61000000B24287E8C5947C01CEBE2361A9037C0'::geography, '2000'::double precision, true))",
              "Rows Removed by Filter": 5,
              "Shared Hit Blocks": 57,
              "Shared Read Blocks": 7
            }
          ]
        }
      ]
    },
    "Planning Time": 0.592,
    "Execution Time": 21.304
  }
]
```

---

## Leitura do plano

| Evidência | Onde | O que significa |
|---|---|---|
| `"Node Type": "Index Scan"` | nó folha | Não há `Seq Scan` em lugar nenhum do plano |
| `"Index Name": "idx_missao_origem"` | nó folha | É o índice GiST da V8, não outro |
| `"Index Cond": (origem && _st_expand(…, '2000'))` | nó folha | O operador `&&` é a sobreposição de bounding box do GiST. É esta condição que desce no índice |
| `"Rows Removed by Index Recheck": 0` | nó folha | O filtro por bounding box já foi exato aqui; nenhuma linha precisou ser descartada na recheck |
| `"Actual Rows": 5` de 200 000 | nó folha | Seletividade ~2,5×10⁻⁵ — o regime em que índice ganha de varredura, e o planner concordou |
| `"Shared Read Blocks": 7` | nó folha | Sete blocos lidos do disco. Uma varredura sequencial de 200 mil linhas leria milhares |
| `"Execution Time": 21.304` | raiz | 21 ms sobre 200 mil linhas |

O `Filter` com `st_dwithin` no mesmo nó é o comportamento normal e esperado do PostGIS: o índice
filtra por bounding box (`&&`, barato e aproximado) e a função exata roda só sobre os sobreviventes.
`Rows Removed by Filter: 5` é isso acontecendo — 10 linhas passaram pela caixa, 5 estavam de fato
dentro do raio circular de 2 km.

O `Sort` acima do `Index Scan` ordena 5 linhas em memória (`Sort Space Used: 25` kB). O índice GiST
não fornece ordem por distância, então o `ORDER BY ST_Distance` é sempre um sort — irrelevante neste
volume, e limitado pelo `LIMIT`.

---

## Por que o mesmo `EXPLAIN` na base de dev NÃO mostra o GiST

Rodar a consulta contra o banco do `docker compose` — 12 missões do seed, 5 delas `ABERTA` — produz
um plano **sem** `idx_missao_origem`. Isso costuma ser lido como defeito, e não é. Medido na
auditoria F6, com `enable_seqscan = on` nos três casos:

| Variante | Plano escolhido | Tempo |
|---|---|---|
| Query real, **com** filtro de status | `Index Scan using idx_missao_expiravel` — `ST_DWithin` vira `Filter` | 4,761 ms |
| Mesma query, **sem** o filtro de status | `Index Scan using idx_missao_origem` — GiST, com `Index Cond` | 4,917 ms |
| Com status, derrubando o índice parcial (em transação revertida) | `Index Scan using idx_missao_status_criada` | **0,022 ms** |

A terceira linha é a que fecha o argumento: com esse volume, o caminho por status é **200× mais
rápido** que o geoespacial. Cinco linhas `ABERTA` entre doze tornam o filtro de status mais seletivo
que o de proximidade, e forçar o GiST degradaria a consulta.

**Não há `Seq Scan` em nenhuma das variantes, e não existe `ST_Distance(...) < X` no código** — o
predicado é sempre `ST_DWithin`, que é o que usa índice; `ST_Distance` aparece apenas na projeção.

É exatamente por isso que a prova acima usa 200 mil linhas **todas `ABERTA`**: com status misturado,
o planner poderia legitimamente preferir o B-tree, e a evidência seria sobre o índice errado. A
condição para o GiST ser escolhido é ser o caminho mais seletivo — o que exige volume, não apenas a
existência do índice.

**Consequência prática:** para verificar o índice geoespacial, rode `IndiceGeoespacialTest`. Um
`EXPLAIN` manual na base de seed não é evidência nem a favor nem contra.

---

## Reproduzir

```bash
cd services/api && ./mvnw -Dtest=IndiceGeoespacialTest test
```

O teste é `@Tag("geo")` e roda contra Testcontainers, **não** contra o banco de `docker compose up`.
Motivo: as 200 mil linhas entram numa transação que sofre rollback, o que deixa tuplas mortas e
inchaço físico em `missao` até o autovacuum passar. Num container descartável o custo é aceitável;
num banco de desenvolvimento persistente, não. Há ainda uma limpeza por prefixo (`eeee0000-…`) em
`@AfterAll`, como cinto e suspensório caso alguém remova o `@Transactional` sem notar que a tabela é
compartilhada por toda a suíte.

"Aceitável" não é "gratuito", e vale registrar: o inchaço fica visível para os testes que rodam
depois, porque o container é singleton para a JVM inteira. Se a suíte começar a ficar lenta em
classes que consultam `missao`, este teste é o primeiro suspeito — e a correção é um `VACUUM` no
`@AfterAll`, não reduzir a carga, que descaracterizaria a prova.

---

## O que esta evidência **não** garante

Acrescentada em 2026-08-17: a convenção deste diretório exige que todo arquivo feche declarando seus
limites, e esta era a única evidência sem a seção.

- **Não é medição de desempenho.** O `EXPLAIN ANALYZE` prova a **escolha do plano** — `Index Scan`
  sobre o GiST em vez de `Seq Scan`. Os tempos que aparecem na saída são de um container efêmero,
  com cache frio, numa máquina de desenvolvimento: não são latência de produção, não foram repetidos
  e não sustentam nenhum número de SLA. Medição de carga é a F12b, pendente.
- **Não prova o comportamento com os dados reais.** As 200 mil linhas são geradas sinteticamente e
  distribuídas de forma uniforme. Distribuição real de missões é concentrada por bairro, o que muda
  a seletividade e pode mudar o plano.
- **Não prova que o índice é usado por todas as consultas geoespaciais.** O teste exercita o radar
  de proximidade. As demais consultas de `ConsultasGeoespaciais` não foram submetidas a `EXPLAIN`.
- **Não prova estabilidade do plano.** O planejador do PostgreSQL decide por estatística; uma
  mudança de volume, de `work_mem` ou um `ANALYZE` desatualizado pode levá-lo a outra escolha. O que
  está provado é que, com este volume e estas estatísticas, ele escolhe o índice.
