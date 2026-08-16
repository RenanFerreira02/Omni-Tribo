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
| [`f13-conservacao-por-categoria.md`](f13-conservacao-por-categoria.md) | 2026-08-16 | **AJUDA cunha (Δ=+30), TRIBO conserva (Δ=0)** — e a reconciliação responde `integro=true` nos dois casos | `bash tools/evidencias/conservacao-por-categoria.sh` |
| [`f13-make-test.md`](f13-make-test.md) | 2026-08-16 | 637 testes no backend e 179 no mobile, verdes, com SpotBugs e os dois gates JaCoCo | `make test` |

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

## Convenção

- Nome: `f<fase>-<assunto>.md`.
- Todo arquivo abre com **data**, **fase** e **comando**, e fecha com uma seção do que ele **não**
  garante.
- Evidência não se edita para "melhorar" o resultado. Se o número mudou, gere uma evidência nova com
  data nova.
