# Matriz de rastreabilidade — requisito → endpoint/tela → teste → resultado → evidência

**Data:** 2026-08-15
**Branch:** `develop`
**Como reproduzir:** `cd services/api && ./mvnw verify` · `cd apps/mobile && npm run typecheck && npm run lint && npm test`
**Escopo:** os 15 requisitos principais desta fase de qualidade, incluindo os **não implementados**,
justificados em §2.3.

> **Sobre a origem dos requisitos.** Não existe no repositório um documento de especificação com
> numeração própria: o `grep` por "Fase 4" retorna zero ocorrências, e o PDF em `documentacao/` é o
> relatório de entrega — seu §2.3 é a análise SWOT, não uma lista de requisitos. A numeração abaixo
> foi derivada da especificação alvo desta fase, somada às pendências já registradas no `CLAUDE.md`.
> Se houver um edital com numeração oficial, esta matriz deve ser reordenada para espelhá-la.

---

## §1 Requisitos implementados

| # | Requisito | Endpoint / Tela | Teste | Resultado | Evidência |
|---|---|---|---|---|---|
| 1 | ViaCEP proxiado atrás da nossa fronteira | `GET /api/v1/enderecos/{cep}` | `IntegracoesControllerTest`, `ClientesExternosTest` | ✅ | `integracoes/infra/ClienteViaCep.java`; vocabulário do provedor traduzido (`localidade` → `cidade`) |
| 2 | Open-Meteo proxiado atrás da nossa fronteira | `GET /api/v1/clima?lat&lon` | `IntegracoesControllerTest`, `ClientesExternosTest` | ✅ | `integracoes/infra/ClienteOpenMeteo.java`; ADR 0011 |
| 3 | **Timeout** em conexão e leitura | ambos | `ClientesExternosTest` | ✅ | `ClientesExternosConfig.java:31-38`, `app.integracoes.timeout: PT2S` |
| 4 | **Cache** evitando a segunda chamada ao terceiro | ambos | `ResilienciaClientesExternosTest.cache_evita_a_segunda_chamada_ao_provedor`; `CacheIntegracoesTest` | ✅ | 2 consultas → **1** requisição no servidor de teste. Chave de clima arredondada a 2 casas (~1,1 km) |
| 5 | **Circuit breaker**: abre no limiar e responde por fallback sem chamar o terceiro | ambos | `DisjuntorTest` (10 casos), `ResilienciaClientesExternosTest.disjuntor_abre_no_limiar_e_deixa_de_chamar_o_provedor` | ✅ | Contagem de requisições **estagnada** após a abertura (10 → 10). ADR 0023 |
| 6 | Circuit breaker **volta a fechar após a espera** | ambos | `DisjuntorTest.volta_a_fechar_depois_da_espera`, `ResilienciaClientesExternosTest.disjuntor_volta_a_fechar_depois_da_espera` | ✅ | Relógio injetado avançado em 31 s; sonda única passa e fecha. Sem `sleep`: 10 casos em **0,135 s** |
| 7 | **Retry só em transitório — nunca em 4xx** | ambos | `ResilienciaClientesExternosTest.retry_nao_acontece_em_404` + controle positivo em 500 | ✅ | 404 → **1** requisição; 500 → **2** requisições. Lista BRANCA em `ProtecoesExternas.politica` |
| 8 | **Fallback claro** e degradação definida | ambos | `IntegracoesControllerTest` | ✅ | 503 `application/problem+json`, `type` `servico-externo-indisponivel`; a UI esconde o recurso (ADR 0011). No webhook, `Optional.empty()` — nunca exceção |
| 9 | **`{"erro": true}` do ViaCEP tratado como 404, não como sucesso** | `GET /api/v1/enderecos/{cep}` | `ClientesExternosTest` (formas `"true"` e `true`), `IntegracoesControllerTest.cep_inexistente_responde_404_e_nao_503`, `ResilienciaClientesExternosTest.cep_inexistente_nao_conta_como_falha_do_provedor` | ✅ | `ClienteViaCep.java:68`. E **não conta como falha do disjuntor**: o provedor respondeu 200 e está saudável |
| 10 | **JaCoCo com gate** — 80% global, 85% em domínio | build | execução `check-global` e `check-dominio` | ✅ | Medido: **91,90%** global, **92,01%** domínio (instruções). Gate provado com mínimo 0,99: *"instructions covered ratio is 0.92, but expected minimum is 0.99"* |
| 11 | **ArchUnit bloqueante** | build | `RegrasArquiteturaTest` (3 regras) | ✅ | São `@Test` comuns; falham o `verify` como qualquer teste. 7 módulos no array `MODULOS` |
| 12 | **SpotBugs sem HIGH** | build | `spotbugs:check` | ✅ **excedente** | `BugInstance size is 0`. O projeto reprova a partir de **Medium**, que engloba HIGH |
| 13 | **ESLint e `tsc --noEmit` limpos** | app | `npm run lint`, `npm run typecheck` | ✅ | Ver §3 |
| 14 | **Teste de contrato do OpenAPI** contra os endpoints reais | `/v3/api-docs` | `ContratoOpenApiTest` (4 casos) | ✅ | Compara nos dois sentidos + declaração de autenticação. **Achou um defeito real** — ver §2.1 |
| 15 | **Gitleaks no histórico completo** | CI | `security.yml` — execução real | ✅ | **48 runs, job `gitleaks` verde em todos**, 2–3 s com `fetch-depth: 0`. Evidência: [`f13-ci-github-actions.md`](../evidencias/f13-ci-github-actions.md) §2 |
| 16 | **Teste de carga dos três caminhos críticos** (F12b) | `GET /missoes/proximas`, `POST /carteira/transferencias`, `POST /webhooks/transportadora` | `tools/carga/carga.js` (k6 v2.2.0), 3 cenários × 5 min | ✅ | **14.967 requisições, 0 respostas 5xx.** Radar a 74,6 req/s com p95 de 4,3 ms e sem joelho; 1.205 transferências na MESMA carteira sem deadlock; os três tetos de rate limit medidos batendo com os configurados. Evidência: [`f21-carga.md`](../evidencias/f21-carga.md) |
| 17 | **Diagrama de confiabilidade do modelo de risco** | — | `AvaliadorCalibracao` + 3 casos em `ModeloRiscoTreinoTest`, dentro do `verify` | ✅ | Frequência observada de **9,5% a 55,5%** nas cinco faixas; erro de calibração **0,0179**; Brier **0,1485** contra **0,1798** do chute constante (**17,4%** do erro eliminado). Ver [`modelo-previsao.md`](modelo-previsao.md) |
| 18 | **Mutation testing em `missoes.dominio` e `carteira.dominio`** | — | profile `mutacao` (PIT 1.25.9), **sem gate** | ✅ | **349/494 mutantes mortos (70,6%)**, cobertura de linha das mutadas 95%. O número entra no relatório, não na porta. Achado: quatro fronteiras de saldo e de teto sem teste no valor exato. Ver [`mutacao.md`](mutacao.md) |

---

## §2 Achados e ressalvas

### 2.1 Defeito encontrado e corrigido nesta fase

`GET /api/v1/auth/me` e `POST /api/v1/auth/logout` **exigem JWT** pela cadeia de segurança — só
`login`, `registrar`, `refresh`, `ping` e o webhook são `permitAll` —, mas o OpenAPI os descrevia
como **anônimos**, porque o `AuthController` era o único controller sem `@SecurityRequirement`.
Quem integrasse pela documentação escreveria um cliente que toma 401.

Achado por `ContratoOpenApiTest.toda_operacao_protegida_declara_autenticacao_no_schema`, e corrigido
anotando as duas operações. **Vale notar por que essa asserção existe**: as duas comparações de
caminho são quase tautológicas, porque o springdoc deriva os paths do mesmo
`RequestMappingHandlerMapping` que o teste consulta — endpoint novo nasce documentado sozinho. Isso
foi **verificado na prática**, acrescentando um endpoint temporário: o teste passou. Só ao marcá-lo
com `@Hidden` a comparação acusou. A asserção de segurança compara contra outra fonte — a cadeia de
filtros — e é ela que encontrou o defeito acima.

### 2.2 Requisito que não foi executado — em ambiente nenhum

**Dependency-Check sem alta/crítica** — configurado e ligado, **não executado até o fim**.

O plugin está no profile `seguranca` com `failBuildOnCVSS=7` e arquivo de supressões. O job de CI
existe, mas **nunca completou uma execução**: falhou nas 4 vezes em que rodou, sempre em 25–36 s,
sem produzir relatório. A execução local reprova pela mesma causa:

```
UpdateException: Error updating the NVD Data
  caused by NvdApiException: Invalid API Key, length of 0 too short to provided a masked partial key
```

O OWASP Dependency-Check 13.0.0 **exige uma chave da API da NVD** para montar a base local; ela é
gratuita mas depende de cadastro. O que ficou provado é a fiação: o plugin executa, lê o arquivo de
supressões e chega à etapa de aquisição de dados — falha só ali. Fica pendente de
`-Dnvd.api.key=$NVD_API_KEY`.

**Reconferido em 2026-08-24, e a frase acima sobreviveu ao teste** — ver
[`f21-dependency-check.md`](../evidencias/f21-dependency-check.md). A hipótese era que ela estivesse
imprecisa: o erro citado é o da string VAZIA, e "sem chave nenhuma" poderia cair em acesso anônimo à
NVD. Não cai. Com a variável **ausente** do ambiente e nada injetando `nvd.api.key`, o build falha em
**8,6 s** com o **mesmo** `Invalid API Key, length of 0`.

O fato novo é esse: **ausente e vazia produzem erro idêntico**, porque o plugin normaliza "sem chave"
para string vazia antes de validar. Ou seja, **a mensagem engana em uma das duas direções** — quem a
lê procura uma chave errada, e a causa pode ser não haver chave. É por isso que a armadilha do
`${env.*}` custou duas depurações aqui. Quem for depurar deve ler a SEGUNDA linha do erro,
`NoDataException: No documents exist`, que é a que diz o que de fato aconteceu.

**O secret também não existia no GitHub, e isso deixou o workflow `Security Scan` vermelho de
2026-08-15 a 2026-08-17** — estado registrado naquela data e **não reconferido em 2026-08-24**, por
não haver `gh` nesta máquina — quatro execuções, todas reprovadas por este job, enquanto o `gitleaks`
passava em todas. Nenhum documento registrava esse efeito até a evidência
[`f13-ci-github-actions.md`](../evidencias/f13-ci-github-actions.md). O conserto de 2026-08-17 moveu
o job para agendamento semanal e o condicionou à existência da chave, com aviso explícito quando ela
falta — o workflow deixa de ser vermelho **sem** passar a alegar que varreu algo.

### 2.3 Requisitos NÃO implementados, com justificativa

| # | Requisito | Por que não foi feito |
|---|---|---|
| 19 | **Status obrigatório para merge** | É configuração do GitHub (Settings → Branches), não de arquivo versionado: exige permissão de admin e o `gh`, ausente nesta máquina. O que **era** pré-requisito técnico foi feito: os filtros `paths:` saíram do nível de workflow e viraram condicional de job, porque um status obrigatório cujo workflow é pulado por `paths:` **nunca reporta** e trava o PR em *"Expected — Waiting for status to be reported"* para sempre |
| 20 | **Gate de cobertura de BRANCH** | Branch está em **74,97%**. Um gate aqui reprovaria o build imediatamente, e a única saída seria relaxar a régua depois — o que as regras do projeto proíbem explicitamente. Declarado como dívida MEDIDA, não como esquecimento. O caminho honesto é subir a cobertura de branch primeiro e só então ligar o gate |
| 21 | **Carteira de patrocinador** (ENTREGA e AJUDA ainda cunham token) | Pendência #1 do `CLAUDE.md`. Exigir pote para ENTREGA hoje faria membros da tribo custearem a logística do varejista — o inverso do modelo. O financiador correto é o patrocinador, e a mecânica (`FinanciamentoMissao`) já existe; falta a carteira debitar de fato |
| 22 | **Soak, bancada distribuída e medição do esgotamento do POOL** | A F12b fechou em 2026-08-25 com medição local ([`f21-carga.md`](../evidencias/f21-carga.md)), e o que sobra é o que ela declara não provar: 5 min por cenário não é soak, uma máquina não é bancada distribuída, e **o pool de conexões nunca chegou a ser pressionado** porque o rate limit barrou antes. Bancada distribuída e SLO contratual seguem fora de escopo por decisão registrada no `CLAUDE.md`; soak e pool são medição que ninguém fez |
| 23 | **Busca de destinatário por handle** (transferência ainda exige UUID digitado) | Pendência #3 do `CLAUDE.md`, e é **omissão deliberada de privacidade**: listar membros daria a qualquer autenticado um mapa social do bairro. A saída é busca por handle exato ou convite, e a decisão não é técnica |
| 24 | **Validação do modelo de risco com dados reais** | Os dados são sintéticos e isso está declarado em todo lugar (ADR 0022, `modelo-previsao.md`). Nenhuma métrica publicada diz respeito à operação |
| 25 | **Métricas do disjuntor no Actuator** | O Resilience4j as publicaria de graça no Micrometer; com disjuntor próprio, o sinal hoje é o log (`WARN` na abertura, `INFO` no fechamento). Aceitável num MVP local — Prometheus e Grafana foram cortados do escopo de propósito |

---

## §3 Resultado da verificação local

Saída completa e colada em [`verificacao-2026-08-15.md`](verificacao-2026-08-15.md). **Isto é
execução local, não estado do CI** — o do CI está em
[`f13-ci-github-actions.md`](../evidencias/f13-ci-github-actions.md). Resumo:

| Área | Comando | Resultado |
|---|---|---|
| Backend | `./mvnw verify` | ✅ **706 testes**, 0 falhas, 0 erros, 2 pulados — **1m03s** (2026-08-25) |
| Cobertura | `jacoco:check` ×2 | ✅ *All coverage checks have been met* |
| Análise estática | `spotbugs:check` | ✅ *BugInstance size is 0* |
| Formatação | `spotless:check` | ✅ 328 arquivos limpos |
| Dependências | `./mvnw -Pseguranca verify` | ⚠️ requer `NVD_API_KEY` — ver §2.2 |
| Mutação | `./mvnw -Pmutacao …:mutationCoverage` | ✅ **349/494 (70,6%)**, sem gate — ver [`mutacao.md`](mutacao.md) |
| Carga | `bash tools/carga/executar.sh` | ✅ **14.967 req, 0 respostas 5xx** — ver [`f21-carga.md`](../evidencias/f21-carga.md) |

---

## O que esta fase NÃO garante

- **A varredura de dependências não produziu resultado.** Nenhuma afirmação sobre CVE nas
  dependências do backend é sustentada por esta fase — só a configuração do gate é.
- **O gate de cobertura não mede qualidade de teste.** 91,90% de instruções diz que o código foi
  executado, não que o comportamento foi verificado. Cobertura de branch, que chega mais perto
  disso, está em 74,97% e **sem gate**.
- **O teste de contrato não valida schemas de corpo.** Compara caminhos, verbos e declaração de
  autenticação; não confere se o `MissaoResponse` documentado tem os mesmos campos que o real.
- **O disjuntor não protege contra provedor oscilante.** Falha, ok, falha, ok mantém o contador de
  falhas consecutivas zerado e o circuito nunca abre. É a decisão certa aqui — metade das
  requisições está sendo atendida —, mas é uma limitação real do critério escolhido.
- **Nada disto foi exercitado contra os provedores reais.** ViaCEP e Open-Meteo aparecem na suíte
  apenas como servidores locais de teste; o comportamento deles sob falha verdadeira é suposição
  informada, não medição.
- **O status obrigatório de merge não está ativo.** Até que seja configurado no GitHub, todos os
  gates desta fase continuam sendo conselho, não barreira: um PR pode ser mesclado com o CI vermelho.
