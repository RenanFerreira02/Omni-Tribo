# Desempenho — a medição que precede qualquer otimização

**Data:** 2026-09-11 · **Branch:** `feat/f15-performance`
**Comandos:**

```bash
make reset && cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
bash tools/carga/executar.sh                                   # k6, dois processos
cd services/api && ./mvnw -Dtest=ContagemDeQueriesTest test     # queries por requisição
cd services/api && ./mvnw -Dtest=FanOutContagemDeQueriesTest test
cd services/api && ./mvnw -Dtest=PlanoConsultasQuentesTest test # EXPLAIN sob volume
cd apps/mobile && npx jest --testPathPattern=renderizacao       # renders por interação
```

**Resultado:** nenhuma otimização foi aplicada. Este documento é só o *antes* — e três das hipóteses
que o motivaram foram **refutadas** pela medição.

---

## 0. Sem prefixo de fase, e o motivo

O diretório convenciona `f<fase>-<assunto>.md`. Não existe fase numerada para este trabalho:
`PROGRESSO.md` vai até F21 e não tem linha para ele. Inventar `f22-` criaria contradição com a
tabela de fases, então este arquivo segue o precedente de
[`impacto-conferido-por-sql.md`](impacto-conferido-por-sql.md), o outro arquivo sem prefixo, pela
mesma razão declarada lá.

## 1. Ambiente — e a coluna que faltava em `f21-carga.md`

| | |
|---|---|
| Máquina | Intel Core i5-13450HX, 16 núcleos, 15 GiB RAM |
| SO | Fedora, kernel 7.1.13-200.fc44.x86_64 |
| **Governor da CPU** | `powersave` · perfil de plataforma `balanced` · AC conectado |
| **Frequência durante a medição** | média de **1.353 MHz** nos 16 núcleos, contra **4.600 MHz** de máximo |
| Carga do sistema | load average 1,05 — a máquina estava ociosa |
| Banco | PostgreSQL 16 + PostGIS 3.5 (`postgis/postgis:16-3.5`), container único, podman |
| Runtime | OpenJDK 21.0.12, Spring Boot 4.1, perfil `dev`, pool Hikari no default (10) |
| Rede | loopback |
| Dados | seed V900–V906, `make reset` imediatamente antes |

**As duas linhas em negrito são novas, e elas mudam como se lê a comparação com
[`f21-carga.md`](f21-carga.md).** Aquele documento registra máquina, SO, banco, runtime, rede e
dados — mas não governor nem frequência. Sem esses dois campos, as latências de duas execuções não
são comparáveis, e a §2 mostra por quê com número.

## 2. A carga reproduziu o MESMO trabalho, em ~3× o tempo

Os três cenários de 2026-08-25 rodaram sem um parâmetro alterado. Os desfechos de negócio saíram
**idênticos**, dígito por dígito:

| | 2026-08-25 | 2026-09-11 |
|---|---:|---:|
| Requisições dos três cenários | 14.967 | **14.967** |
| Iterações do radar | 10.349 | **10.349** |
| Transferências com 201 | 1.205 | **1.205** |
| Respostas 422 (regra de negócio) | 269 | **269** |
| Webhook `CONVERTIDA` | 57 | **57** |
| Webhook `RECUSADA` | 631 | **631** |
| 5xx | 0 | **0** |

As latências, não:

| Métrica | 2026-08-25 | 2026-09-11 | razão |
|---|---:|---:|---:|
| `radar_cache_frio_ms` p50 | 3,43 ms | 9,58 ms | 2,8× |
| `radar_cache_quente_ms` p50 | 2,01 ms | 6,62 ms | 3,3× |
| p95 do radar no patamar de 74,6 req/s | 4,3 ms | 12,1 ms | 2,8× |

**A razão entre 4.600 MHz e 1.353 MHz é 3,4×.** A carga é a mesma, a máquina é a mesma, o código é
o mesmo — o que mudou foi o relógio da CPU. **Não há regressão de desempenho a explicar**, e
nenhuma das diferenças acima autoriza conclusão sobre o software.

A consequência prática é que **latência é o instrumento mais fraco desta investigação**. Contagem de
query, plano de execução e contagem de render não dependem da frequência da CPU, e é neles que as
seções 4 a 6 se apoiam.

### O radar continua sem joelho

```
| Janela | req | req/s | p50 (ms) | p95 (ms) | p99 (ms) | 2xx | 429 | 422 | outros |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0–30s | 180 | 6.0 | 12.3 | 19.2 | 24.1 | 180 | 0 | 0 | 0 |
| 120–150s | 741 | 24.7 | 7.6 | 12.5 | 14.1 | 741 | 0 | 0 | 0 |
| 270–300s | 2241 | 74.7 | 7.5 | 12.1 | 13.3 | 2241 | 0 | 0 | 0 |
```

O cache por geohash economiza **31% do p50** (9,58 → 6,62 ms), contra os 41% de agosto. A conclusão
de lá não muda: o cache é economia real, e quem segura o sistema é o índice.

## 3. Cenário novo — as leituras que nenhum teste de carga tinha tocado

`GET /missoes` com filtro, `GET /missoes/{id}` e `GET /carteira/lancamentos` nunca haviam sido
exercitados sob carga. Agora são, num **processo k6 próprio**.

**10.349 requisições, 100% HTTP 200, zero 429, zero erro.**

```
| Janela | req | req/s | p50 (ms) | p95 (ms) | p99 (ms) | 2xx | 429 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 0–30s | 180 | 6.0 | 14.2 | 23.3 | 34.0 | 180 | 0 |
| 150–180s | 1041 | 34.7 | 10.4 | 14.3 | 16.2 | 1041 | 0 |
| 270–300s | 2241 | 74.7 | 9.5 | 13.7 | 15.7 | 2241 | 0 |

| Métrica | amostras | p50 (ms) | p95 (ms) | p99 (ms) |
|---|---:|---:|---:|---:|
| `detalhe_ms` | 2587 | 6.61 | 8.43 | 9.60 |
| `extrato_ms` | 2587 | 8.92 | 11.95 | 13.90 |
| `lista_ordem_indexada_ms` | 2588 | 11.74 | 15.44 | 18.06 |
| `lista_ordem_sem_indice_ms` | 2587 | 11.52 | 15.20 | 17.89 |
```

**As duas ordenações empataram** — 11,74 ms contra 11,52 ms, com a "sem índice" marginalmente mais
rápida. Lido sozinho, esse par diria que ordenar por uma coluna sem índice não custa nada. A §5
mostra que isso é verdade **só porque o banco de dev tem 77 missões**, e é o mesmo argumento que
`f6-explain-analyze.md` usa para recusar EXPLAIN sobre tabela pequena.

### O defeito do instrumento, e ele foi medido

A primeira tentativa pôs o cenário como um quarto bloco em `startTime: '16m30s'`, no mesmo processo.
Resultado: **1.180 respostas 401 e nenhuma 200**. O access token sai do `setup()`, que roda uma vez
em t=0, e vale 900 s.

Os **1.822 × 429** que vieram junto são o segundo efeito do mesmo erro, e ele é instrutivo: com o
JWT inválido, `RateLimitFilter.resolverChave` não encontra o `sub` e cai para `"ip:" + endereço` —
os sessenta VUs, que deveriam ter um balde por usuário, passam a dividir **um balde de 300/min**.
Um cenário mal colocado no tempo não mede devagar; mede outra coisa.

## 4. Queries por requisição — e o N+1 que existe

Instrumento novo: `ContadorDeQueries`, um contador no nível do **JDBC** (não do `StatementInspector`
do Hibernate, que não enxergaria o `JdbcClient` de `ConsultasGeoespaciais` — onde vive todo `ST_*`
do sistema).

| Caminho | Queries | Cresce com N? |
|---|---:|---|
| `GET /missoes/{id}` | 1 | não |
| `GET /missoes` | 2 (página + count) | **não** |
| `GET /carteira/lancamentos` | 3 (carteira + página + count) | **não** |
| `GET /missoes/proximas` — miss com resultado | 2 (PostGIS + `findAllById`) | não |
| `GET /missoes/proximas` — hit | 0 | — |
| **fan-out de entrega falida** | **2 + 4N** | **sim, linearmente** |

**Não há N+1 de associação em lugar nenhum, e não pode haver:** o backend não tem uma única
`@ManyToOne`/`@OneToMany` em 320 arquivos de `src/main/java` — toda FK é `UUID` escalar e
`open-in-view` é `false`. A proteção é uma escolha de modelagem, não uma trava, e é por isso que o
contador ficou commitado: hoje, trocar `UUID criadorId` por `@ManyToOne Usuario criador` para expor
o handle na listagem tornaria `MissaoService.listar` um 1+N sem nada ficar vermelho.

### O fan-out custa 4 queries por destinatário

```
fan-out: 22 queries para 5 destinatários, 82 para 20 — 4 por destinatário
```

Ajuste exato de `2 + 4N`. Três das quatro se leem no laço de
`DespachanteAlertaService.anunciarMissaoDeRetirada`: deduplicação, teto por hora e escrita. **A
quarta não se lê em lugar nenhum** — `Alerta` tem `@Id` atribuído à mão, sem `@Version` e sem
implementar `Persistable`, então `SimpleJpaRepository.save` conclui que a entidade não é nova e
chama `merge()`, que emite um SELECT antes do INSERT.

Em escala de bairro: **um fan-out para 200 vizinhos custa 802 idas ao banco.**

A medição de agosto não podia ver isto: o seed tem **um** usuário elegível (`fernanda`, V904), e um
laço com N=1 é indistinguível de um fan-out em lote.

### Achado lateral: a suíte roda o PostGIS com privilégio de DONO

Sonda (`SELECT current_user`) pelo `JdbcClient` injetado: responde **`test`**, o dono do banco, não
`omnitribo_app`. A causa é o `@Primary` de `OperadorBancoTestConfig` — a autoconfiguração do Boot
monta `NamedParameterJdbcTemplate`, e daí o `JdbcClient`, a partir do template primário.

**Não é defeito de produção:** lá não existe aquela configuração, o único `DataSource` é o do papel
restrito e o `JdbcClient` herda esse. É lacuna de **fidelidade**: `MigracaoTest` prova em runtime
que a aplicação não apaga o ledger, mas essa prova cobre o caminho JPA. O caminho geoespacial nunca
exercita o papel restrito, e uma escrita introduzida em `ConsultasGeoespaciais` passaria verde na
suíte e falharia com `permission denied` em produção.

## 5. Planos sob volume — 200 mil missões e 500 mil lançamentos

`PlanoConsultasQuentesTest`, no molde de `IndiceGeoespacialTest`, com as duas recusas dele repetidas:
nada de EXPLAIN sobre a tabela como está, e **nenhum `enable_*` tocado**.

| Consulta | Plano escolhido | Execução |
|---|---|---:|
| `ORDER BY criada_em DESC` | Index Scan `idx_missao_status_criada` | **0,035 ms** |
| `ORDER BY tokens_recompensa DESC` | **Seq Scan (66.671 linhas) → Sort → Gather Merge** | **23,891 ms** |
| filtro `lower(cidade)` | Index Scan `idx_missao_status_criada` | 0,033 ms |
| `WHERE status = ?` sem ordenação | Index Scan `idx_missao_status` | 0,014 ms |
| extrato paginado | Index Scan `idx_lancamento_carteira_criado` | 0,034 ms |
| `count(*)` da paginação do extrato | Bitmap Heap Scan, 500 linhas | 0,414 ms |

**682× entre as duas ordenações da MESMA listagem** — e a coluna é escolhida pelo cliente, em
`?ordenarPor=`. O javadoc de `MissaoFiltroRequest` explica que a whitelist existe porque o resolver
de `Pageable` do Spring Data "permite ordenar por coluna sem índice — DoS barato". A whitelist
restringe QUAIS colunas, e **duas das quatro que ela oferece (`TOKENS_RECOMPENSA`, `XP_RECOMPENSA`)
não têm índice nenhum**. A defesa está incompleta em relação ao próprio motivo declarado.

### Hipótese refutada: `lower(cidade)` não varre a tabela

A previsão era Seq Scan, por não existir índice funcional sobre `lower(cidade)` — `uk_usuario_handle_lower`
(V27) prova que o projeto conhece o padrão. Medido: **Index Scan em `idx_missao_status_criada`,
0,033 ms**. O `LIMIT 20` sobre a ordenação indexada deixa o planner caminhar o índice e aplicar o
filtro linha a linha, e com cidades frequentes ele encontra as 20 quase imediatamente. A hipótese
estava errada, e nenhum índice novo se justifica por ela.

### Tamanho dos índices de `missao` com 200 mil linhas

```
idx_missao_origem = 12 MB          missao_pkey = 6184 kB
idx_missao_status_criada = 2480 kB idx_missao_janela = 1296 kB
idx_missao_categoria = 1288 kB     idx_missao_criador = 1288 kB
idx_missao_status = 1280 kB        idx_missao_expiravel = 1280 kB
```

`idx_missao_status (status)` é **prefixo estrito** de `idx_missao_status_criada (status, criada_em
DESC)`. O planner o escolheu para "filtro por status sem ordenação" — forma que a listagem de
produção nunca emite, porque o `Sort` default é `CRIADA_EM`. **Isso não prova que ele é peso morto:**
provar exigiria `pg_stat_user_indexes.idx_scan` sobre carga real, que não existe aqui. O que está
medido é o custo — 1.280 kB por 200 mil linhas, mantidos a cada escrita em `missao`.

## 6. Mobile — renders, medidos com `<Profiler>`

Sem dependência nova: o `<Profiler>` do próprio React, dentro de teste RNTL.

```
radar em lista: 10/50 missões montadas, 50/50 pontos montados
                · 12 commits de atualização, 180,00 ms de render
rodapé não virtualizado: 5 pontos = 28,00 ms, 50 pontos = 56,00 ms (2,00×)
toque no chip ENTREGA: 3 commits, 27,00 ms de render, 10 cards montados
```

**10 de 50 contra 50 de 50 é a assimetria inteira.** As missões passam pela janela de virtualização
da `FlatList`; os pontos de custódia vivem no `ListFooterComponent`, que a lista renderiza inteiro,
fora de qualquer janela. O backend devolve o default de 50 (`PontoCustodiaController`) e o cliente
não passa `limite`. Dobrar de 5 para 50 pontos **dobra** o custo de render da tela.

Outros fatos confirmados por leitura, com arquivo e linha:

- **Zero `React.memo` no app inteiro**, e `renderItem` inline nas quatro `FlatList`
  (`index.tsx:189`, `mapa.tsx:342`, `carteira.tsx:276`, `notificacoes.tsx:122`).
- `?? []` / `flatMap` **fora de `useMemo`** em `carteira.tsx:92`, `mapa.tsx:233-234` e
  `notificacoes.tsx:30` — array novo a cada render, prop `data` da lista sempre diferente.
  `index.tsx:46` é a única das quatro que memoiza.
- **Nove** hooks de query sem `staleTime`, herdando os 15 s de `app/_layout.tsx:22`.
- Leaflet vem do unpkg a **cada montagem** da WebView: medidos **147.552 B de JS (273 ms)** e
  **14.806 B de CSS (55 ms)** desta máquina. Lista ↔ Mapa é um ternário
  (`mapa.tsx:221-238`), então alternar desmonta a WebView e refaz o download.

## 7. Três afirmações desatualizadas encontradas de passagem

Nenhuma é defeito de execução; as três dizem em presente algo que o código não faz mais.

| Onde | O que afirma | O que o código faz |
|---|---|---|
| `services/api/CLAUDE.md:108` | "**cinco** pontos de invalidação" do cache de proximidade, e lista cinco | São **seis**: `criar`, `abrirMissaoDeRetirada`, `atualizar`, `registrarCheckin`, `aplicar` e `expirarUma`. O do webhook ficou de fora da lista |
| `RateLimitFilter.java:73-76` | descreve o vetor de inflar os mapas variando `X-Forwarded-For` | `EnderecoDoCliente` não lê o cabeçalho desde o ADR 0019; o texto é histórico e lê-se como presente |
| `apps/mobile/src/features/impacto/hooks.ts:13` | "`staleTime` zero (o default)" | O default do projeto é `15_000` |

## 8. Auditoria dos sete caches Caffeine

Não há `@Cacheable` no projeto — o `spring-boot-starter-cache` foi recusado de propósito
(`pom.xml:79-87`), porque `@CacheEvict` dispara dentro da transação.

| Cache | TTL | Teto | Guarda falha? |
|---|---|---|---|
| `CacheMissoesProximas` | 30 s | 10.000 | não |
| Clima | `PT10M`, configurável | 5.000 | não |
| Endereço/CEP | **nenhum**, deliberado | 10.000 | **sim**, `Optional.empty()` |
| Sessão | 60 s | 20.000 | **sim**, de propósito |
| 3 baldes de rate limit | `expireAfterAccess` 10 min | nenhum | n/a |
| Balde de webhook | 10 min | nenhum | n/a |
| Bloqueio de login | 10 min / janela×2 | nenhum | n/a |

**Nenhum cache de dados está sem política de despejo.** O de CEP sem TTL está justificado no
javadoc e limitado pelo teto. Os cinco baldes sem `maximumSize` são mitigados pela expiração por
ociosidade **e** pela chave não ser mais forjável (ADR 0019) — o vetor que o javadoc descreve está
fechado.

A chave do cache de proximidade é `(célula de geohash precisão 7, raio, categoria, limite)`, **sem
usuário**. A grade de 3.600 células do cenário 1 fica muito abaixo do teto de 10.000.

## 9. O que isto NÃO prova

- **Não é comparação antes-e-depois.** É a segunda linha de base do projeto, e nenhuma otimização
  foi aplicada. O antes-e-depois é o próximo passo, e só vale entre duas execuções da mesma sessão.
- **As latências não são comparáveis com `f21-carga.md`.** A §2 mostra a causa medida (CPU a 29% da
  frequência máxima) e o efeito (~3×). Comparar percentis entre os dois documentos é erro.
- **O `EXPLAIN` prova escolha de plano, não latência de produção.** Container efêmero, cache frio,
  distribuição sintética e uniforme. Os 23,891 ms do `Sort` dizem que o plano muda de natureza sob
  volume, não quanto ele custaria num banco real.
- **Não se mediu se `idx_missao_status` é usado em produção.** Isso exige `idx_scan` acumulado sob
  carga real. O que há é o tamanho e a constatação de que a forma de consulta que o escolhe não é
  emitida pela listagem.
- **Os números do mobile são de render no jest, não de aparelho.** Servem como razão entre duas
  medições do mesmo teste; nenhum deles é latência percebida no Expo Go, e nenhuma passada de
  TalkBack foi feita (LACUNA L4 continua aberta).
- **A invalidação do cache de proximidade sob rajada concorrente não foi medida.** Ela é global
  (`invalidateAll`) e o gatilho é evento externo: numa rajada de webhook, 57 conversões em dois
  minutos descartam o cache do radar a cada ~2 s. Os cenários k6 são sequenciais de propósito, então
  isso nunca se sobrepôs. É buraco declarado, não resultado.
- **O pool de conexões continua sem ser pressionado.** O cenário de leituras não tomou um único 429,
  e o teto de 10 conexões não chegou perto de saturar.
- **Nada aqui mede soak, segundo nó ou rede real.** As mesmas limitações da §7 de
  [`f21-carga.md`](f21-carga.md) continuam valendo palavra por palavra.
