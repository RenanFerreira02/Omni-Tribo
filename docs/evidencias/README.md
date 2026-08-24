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
| [`f21-dependency-check.md`](f21-dependency-check.md) | 2026-08-24 | **Uma tentativa que FALHOU**, e a hipótese que ela derrubou: o Dependency-Check 13.0.0 não tem acesso anônimo à NVD, e chave ausente produz o mesmo erro de chave vazia. Nenhum CVE listado — nenhuma varredura completou | `./mvnw -Pseguranca verify -DskipTests` (sem `-Dnvd.api.key`) |

> `impacto-conferido-por-sql.md` é o único arquivo **sem prefixo de fase**: o painel de impacto não
> foi entregue como uma fase numerada, e inventar um `f15-` criaria contradição com o
> `PROGRESSO.md`, que é a numeração de verdade. Mesmo motivo pelo qual as duas auditorias do mobile
> não seguem o padrão `FN.md`.

## O que **não** está provado aqui

Vale mais que a lista acima, porque é onde uma banca vai empurrar:

- **Carga e desempenho.** Nenhuma medição de latência, TPS ou concorrência sob carga — é a F12b,
  pendente. Os números de desempenho do documento estratégico (< 200 ms, 1.000 TPS, SLA 99,9%) são
  metas, não medições. Ver [`../DIVERGENCIAS-DOCUMENTACAO.md`](../DIVERGENCIAS-DOCUMENTACAO.md).
- **O modelo de risco com dados reais.** O dataset é **sintético**, com correlações injetadas e
  documentadas. Ver [`../qualidade/modelo-previsao.md`](../qualidade/modelo-previsao.md).
- **Portabilidade de ambiente.** A execução do zero foi feita numa máquina Linux com podman. Não
  prova macOS, Windows nem Docker Desktop.
- **Antifraude de geolocalização.** O que os controles de check-in **não** pegam está listado em
  [`../seguranca/antifraude-geolocalizacao.md`](../seguranca/antifraude-geolocalizacao.md) — spoofing
  com root é mitigável e não eliminável, presença não é execução, conluio não é detectado.
- **Conservação em ENTREGA.** O ciclo de ENTREGA nasce do webhook e envolve ponto de custódia; a
  medição por categoria refez AJUDA e TRIBO. O caso de ENTREGA (Δ=+60) foi medido na
  [auditoria F7](../auditoria/F7.md).
- **Ausência de CVE nas dependências.** A varredura OWASP **nunca concluiu**, nem local nem no CI —
  falta a chave da NVD. O gate está configurado; o resultado não existe. Ver
  [`f13-ci-github-actions.md`](f13-ci-github-actions.md) §1.
- **Conteúdo dos logs do CI.** A evidência de CI cobre *conclusão* de run, job e passo, colhida da
  API pública. O download dos logs exige token com escopo `actions:read` e responde `403` sem ele.

## Convenção

- Nome: `f<fase>-<assunto>.md`.
- Todo arquivo abre com **data**, **fase** e **comando**, e fecha com uma seção do que ele **não**
  garante.
- Evidência não se edita para "melhorar" o resultado. Se o número mudou, gere uma evidência nova com
  data nova.
