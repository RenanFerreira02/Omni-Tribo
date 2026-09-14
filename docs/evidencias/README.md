# Evidências

Saídas **reais** de execução. Nada aqui é escrito à mão: cada arquivo cola o que um comando, um teste
ou uma consulta devolveu, com a data e o comando que o produziu.

A regra do projeto é *medir antes de afirmar*. Este diretório é o outro lado dela — toda afirmação
de garantia no README, no `PROGRESSO.md` ou nos documentos de fase deve apontar para um arquivo
daqui ou de [`../qualidade/`](../qualidade/).

| Evidência | Data | O que prova | Como reproduzir |
|---|---|---|---|
| [`f6-explain-analyze.md`](f6-explain-analyze.md) | 2026-08-07 | O radar geoespacial usa o índice **GiST** (`Index Scan`, não `Seq Scan`), contra PostGIS 3.5 real | `./mvnw -Dtest=IndiceGeoespacialTest test` |
| [`f12-ciclo-ponta-a-ponta.md`](f12-ciclo-ponta-a-ponta.md) | 2026-08-09 | Ciclo completo da missão em 12 passos, com dois usuários reais, pelo cliente HTTP do app | `E2E_API_URL=http://localhost:8080 npm run test:e2e -- --verbose` |
| [`f13-execucao-do-zero.md`](f13-execucao-do-zero.md) | 2026-08-16 | O README funciona seguido literalmente, com **volume e chaves destruídos antes**. Inclui webhook, risco congelado, fan-out e outbox drenada | `make reset` e seguir o [README](../../README.md) |
| [`f13-conservacao-por-categoria.md`](f13-conservacao-por-categoria.md) | 2026-08-16 | **SUPERADA por `f14`.** Media o mundo em que AJUDA cunhava (Δ=+30) e só TRIBO conservava. Fica como registro histórico — o script que ela cita foi reescrito | — |
| [`f14-conservacao-quatro-categorias.md`](f14-conservacao-quatro-categorias.md) | 2026-08-22 | **Δ=0 nas QUATRO categorias**, com o pote de ENTREGA pago pelo patrocinador, e a recusa por falta de saldo respondendo 200 sem criar missão. `integro=true` em todos os pontos | `bash tools/evidencias/conservacao-por-categoria.sh` (ver o doc: o servidor sobe com a varredura acelerada) |
| [`f13-make-test.md`](f13-make-test.md) | 2026-08-16 | 637 testes no backend e 179 no mobile, verdes. **SpotBugs e os dois gates JaCoCo aparecem executando, mas o console colado traz só os cabeçalhos dos plugins** — as linhas de resultado (`BugInstance size is 0`, *All coverage checks have been met*) estão em [`../qualidade/verificacao-2026-08-15.md`](../qualidade/verificacao-2026-08-15.md), de **outra data** | `make test` |
| [`f13-ci-github-actions.md`](f13-ci-github-actions.md) | 2026-08-17 | O histórico **real** do GitHub Actions: 113 runs. Gitleaks verde em 48/48; Mobile CI vermelho de 08-09 a 08-13; `Security Scan` reprovado desde `ca328fc` pelo job de dependências | `curl` na API pública — comando no arquivo |
| [`impacto-conferido-por-sql.md`](impacto-conferido-por-sql.md) | 2026-08-23 | O painel `GET /admin/impacto` **batendo com uma contagem manual por SQL**, métrica a métrica, no mesmo banco e no mesmo instante — inclusive a mediana conferida contra o `percentile_cont` do PostgreSQL. Mostra também a premissa de custo mudando o resultado por configuração | `make reset`, `spring-boot:run`, `bash tools/carrier-mock/enviar.sh`, `curl` e `psql` — todos no doc |
| [`f21-carga.md`](f21-carga.md) | 2026-08-25 | **A medição que faltava para a F12b.** 14.967 requisições, **0 respostas 5xx**: radar a 74,6 req/s com p95 de 4,3 ms e sem joelho, cache por geohash economizando 41% do p50, transferências na MESMA carteira sem um deadlock, e os três tetos de rate limit batendo com os configurados. Achado: o alerta de ponto lotado escreve 631 linhas idênticas sem teto nem dedup | `make reset`, `spring-boot:run`, `bash tools/carga/executar.sh` |
| [`desempenho-antes-de-otimizar.md`](desempenho-antes-de-otimizar.md) | 2026-09-11 | **A segunda linha de base, e o que ela derruba.** A carga repetiu o trabalho de agosto dígito por dígito (14.967 req, 57 CONVERTIDA, 631 RECUSADA, 269 × 422) em **~3× o tempo** — causa medida: CPU a 1.353 MHz contra 4.600 de máximo, campo que `f21-carga.md` nunca registrou. Mede também o que nenhum teste media: **queries por requisição** (o fan-out custa 2+4N), **plano sob 200 mil linhas** (`ORDER BY tokens_recompensa` é **682×** mais lento que por `criada_em`) e **renders no mobile** (50/50 pontos de custódia montados fora da virtualização). Nenhuma otimização aplicada; três hipóteses refutadas | `bash tools/carga/executar.sh` e os três `-Dtest=` no cabeçalho do arquivo |
| [`f21-dependency-check.md`](f21-dependency-check.md) | 2026-08-24 | **Uma tentativa que FALHOU**, e a hipótese que ela derrubou: o Dependency-Check 13.0.0 não tem acesso anônimo à NVD, e chave ausente produz o mesmo erro de chave vazia. Nenhum CVE listado — nenhuma varredura completou | `./mvnw -Pseguranca verify -DskipTests` (sem `-Dnvd.api.key`) |
| [`entrega-final-verificacao-2026-09-12.md`](entrega-final-verificacao-2026-09-12.md) | 2026-09-12 | **A verificação que sustenta a seção Estado do README**, numa execução única e inteira: 755 testes no backend e 225 no mobile, `BugInstance size is 0`, os dois gates JaCoCo com *All coverage checks have been met*, e a cobertura real (92,68% global · 92,60% em `dominio` · **BRANCH em 76,37% e sem gate**). Substitui a citação de `f13-make-test.md`, que é de 2026-08-16 e registra outros números. Documenta duas substituições na skill `/verificar`: `DOCKER_HOST` exportado, e `make ps` no lugar de `docker compose ps` — **o passo 4 da skill está desatualizado** | a skill `/verificar`, com as duas substituições da §1 do arquivo |

> **Dois arquivos sem prefixo de fase**, pelo mesmo motivo. `impacto-conferido-por-sql.md`: o painel
> de impacto não foi entregue como fase numerada, e inventar um `f15-` criaria contradição com o
> `PROGRESSO.md`, que é a numeração de verdade. `entrega-final-verificacao-2026-09-12.md`: a tabela de
> fases do `PROGRESSO.md` **não tem F14 a F17**, todas mergeadas, então um `f18-` contradiria o mesmo
> arquivo. Mesmo motivo pelo qual as duas auditorias do mobile não seguem o padrão `FN.md`.

## O que **não** está provado aqui

Vale mais que a lista acima, porque é onde uma banca vai empurrar:

- **Comparação de latência ENTRE execuções.** A medição de 2026-09-11 provou que o mesmo
  trabalho custa ~3× mais tempo com a CPU em `powersave` a 1.353 MHz. Governor e frequência
  passaram a ser campos obrigatórios do quadro de ambiente; sem eles, dois documentos deste
  diretório não podem ter seus percentis comparados, e `f21-carga.md` não os tem.
- **Carga além de uma máquina e de cinco minutos.** Existe medição desde 2026-08-25
  ([`f21-carga.md`](f21-carga.md)), e ela é de UMA máquina, com k6, JVM e Postgres dividindo os
  mesmos 16 núcleos, sobre dado de seed, por 5 minutos por cenário. Não há soak, não há segundo nó,
  não há dado em volume — e o pool de conexões nunca chegou a ser pressionado, porque o rate limit
  barrou antes. Os números do documento estratégico (< 200 ms, 1.000 TPS, SLA 99,9%) continuam sendo
  metas herdadas, e o medido (75 req/s num cenário, 2 req/s noutro) não os alcança nem pretende. Ver
  [`../DIVERGENCIAS-DOCUMENTACAO.md`](../DIVERGENCIAS-DOCUMENTACAO.md).
- **O modelo de risco com dados reais.** O dataset é **sintético**, com correlações injetadas e
  documentadas. Ver [`../qualidade/modelo-previsao.md`](../qualidade/modelo-previsao.md).
- **Portabilidade de ambiente.** A execução do zero foi feita numa máquina Linux com podman. Não
  prova macOS, Windows nem Docker Desktop.
- **Antifraude de geolocalização.** O que os controles de check-in **não** pegam está listado em
  [`../seguranca/antifraude-geolocalizacao.md`](../seguranca/antifraude-geolocalizacao.md) — spoofing
  com root é mitigável e não eliminável, presença não é execução, conluio não é detectado.
- **Conservação em ENTREGA criada por HUMANO.** Este item já foi mais amplo e ficou desatualizado: o
  ciclo de ENTREGA que nasce do **webhook** tem Δ=0 medido, primeiro em
  [`f14-conservacao-quatro-categorias.md`](f14-conservacao-quatro-categorias.md) e de novo na
  [auditoria de entrega final](../auditoria/entrega-final.md), com o pote pago pelo patrocinador na
  própria conversão. O que continua **fora** de toda medição é a ENTREGA que um usuário cria no app:
  ela é a única `fonte_pote = CUNHAGEM` que sobrou (ADR 0025), logo a única em que a soma **sobe** na
  conclusão. Nenhum ciclo do script a exercita — ele usa o webhook para ENTREGA, que é o caso com
  patrocinador. O caso foi medido pela primeira vez no
  [adendo de 2026-09-13](../auditoria/entrega-final.md#adendo-de-2026-09-13--a-cunhagem-que-sobrou-medida):
  **Δ=+22**, crédito sem contraparte, com a reconciliação respondendo `integro=true`. O Δ=+60 da
  [auditoria F7](../auditoria/F7.md) é o registro histórico de quando ENTREGA e AJUDA cunhavam.
- **Ausência de CVE nas dependências.** A varredura OWASP **nunca concluiu**, nem local nem no CI —
  falta a chave da NVD. O gate está configurado; o resultado não existe. A tentativa mais recente
  está medida em [`f21-dependency-check.md`](f21-dependency-check.md), que também derrubou a
  hipótese de haver acesso anônimo à NVD; o histórico do job vermelho está em
  [`f13-ci-github-actions.md`](f13-ci-github-actions.md) §1.
- **Conteúdo dos logs do CI.** A evidência de CI cobre *conclusão* de run, job e passo, colhida da
  API pública. O download dos logs exige token com escopo `actions:read` e responde `403` sem ele.

## Convenção

- Nome: `f<fase>-<assunto>.md`.
- Todo arquivo abre com **data**, **fase** e **comando**, e fecha com uma seção do que ele **não**
  garante.
- Evidência não se edita para "melhorar" o resultado. Se o número mudou, gere uma evidência nova com
  data nova.
