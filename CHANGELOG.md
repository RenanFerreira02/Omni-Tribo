# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/).
Uma entrada por **fase** do projeto — a numeração de fases é a de
[`docs/PROGRESSO.md`](docs/PROGRESSO.md), não a do histórico do git, que engana.

> **Por que as datas são próximas:** o projeto foi construído em fases curtas e sequenciais, com
> auditoria ao fim de cada bloco. As rodadas de auditoria aparecem como entradas próprias porque
> mudaram o código, e não só o julgamento sobre ele.

---

## [F13.1] — 2026-08-17 · Conserto do CI e revisão de evidência

Entrada própria, e não emenda na F13, porque **mudou artefato e corrigiu afirmação publicada** — não
foi só polimento de texto.

### Corrigido
- **O workflow `Security Scan` estava vermelho desde 2026-08-15** (4 execuções). O job `gitleaks`
  passava em todas; quem reprovava era o `dependencias`, abortando em 25–36 s por ausência do secret
  `NVD_API_KEY` (`Invalid API Key, length of 0`). O job passou a rodar em `schedule` semanal +
  `workflow_dispatch`, com o passo de varredura guardado por `if: env.NVD_API_KEY != ''` e um
  `::warning` explícito quando a chave falta. **A varredura continua sem ter rodado** — o workflow
  deixa de ser vermelho sem passar a alegar que varreu algo.
- **`if-no-files-found: error` no upload do relatório.** No padrão (`warn`), o passo concluía com
  sucesso em 0 s sem encontrar arquivo nenhum: o passo que existia para dar visibilidade era o que
  escondia que o Dependency-Check nunca produziu relatório.
- **Contagem de módulos nativos:** eram **22**, não 12, todos na versão exata fixada pelo SDK 57
  (`docs/PROGRESSO.md`). A contagem errada enfraquecia o argumento de que o app roda no Expo Go.
- **Quatro afirmações sem evidência** em `docs/qualidade/matriz-rastreabilidade.md`: o ✅ do Gitleaks
  vinha do YAML e não de execução; a §2.2 atribuía a falta da varredura a "este ambiente" quando o
  secret também não existe no GitHub; "o job de CI **pronto**" descrevia um job que nunca completou;
  e a §3, intitulada "Resultado do **pipeline**", listava saída local.
- **`docs/evidencias/README.md`** creditava ao `f13-make-test.md` a prova do SpotBugs e dos dois
  gates JaCoCo — o console colado ali traz só os cabeçalhos dos plugins, e as linhas de resultado são
  de outra data (`verificacao-2026-08-15.md`).
- **`docs/evidencias/f6-explain-analyze.md`** era a única evidência sem a seção "o que **não**
  garante" que a convenção do próprio diretório exige.

### Adicionado
- `docs/evidencias/f13-ci-github-actions.md` — o histórico **real** do GitHub Actions colhido da API
  pública: 113 runs, o job `gitleaks` verde em 48/48, o `Mobile CI` vermelho em nove execuções
  seguidas de 08-09 a 08-13, e o `Security Scan` reprovado desde `ca328fc`. Sustenta por execução
  duas afirmações que antes só existiam como texto.

### Notas
- As medições de flake de 2026-08-13 (`221 ms → 2110 ms`, `1,8 s` contra `5 min 2 s`, "28 rodadas
  verdes") foram **mantidas e marcadas como diagnóstico da época sem log retido**. Apagar o registro
  de um diagnóstico correto é pior que declarar que ele não tem arquivo — passá-lo por evidência
  também seria.
- Os números do app Kotlin no comparativo vêm de repositório **externo** e não são reproduzíveis
  neste; a procedência passou a estar dita no próprio documento.

---

## [F13] — 2026-08-16 · Entrega final

### Adicionado
- **Sete diagramas Mermaid** em `docs/diagramas/`: C4 de contexto e contêineres, arquitetura-alvo em
  escala (marcada como não implementada), máquina de estados, sequência do ciclo da missão,
  sequência da entrega falida, fluxo econômico e ER do banco. Todos validados por **renderização**.
- `docs/EVOLUCAO-ARQUITETURAL.md` — linha do tempo das decisões e a história completa do defeito
  econômico: como foi detectado, por que a reconciliação não o pegou, e a distinção entre as
  invariantes de reconciliação e conservação.
- `docs/COMPARATIVO-TECNOLOGIAS.md` — Flutter × Kotlin nativo × React Native, ancorado em artefato
  real, com o custo que a escolha por Expo cobrou.
- `docs/DIVERGENCIAS-DOCUMENTACAO.md` — divergências entre a implementação e o PETI da entrega
  acadêmica, com citação literal.
- `docs/ROTEIRO-DEMO.md` — demonstração de 10 minutos cronometrada, com plano B por bloco.
- `docs/evidencias/f13-execucao-do-zero.md`, `f13-conservacao-por-categoria.md`, `f13-make-test.md`
  e o índice `docs/evidencias/README.md`.
- `tools/evidencias/conservacao-por-categoria.sh` — script que mede a invariante de conservação por
  categoria contra o banco de pé.
- `apps/mobile/eas.json` com perfis de build, e o procedimento documentado. **Nenhum build foi
  executado neste ciclo.**
- `make test` passa a rodar `./mvnw verify` e `npm test` de fato.

### Corrigido
- **README**: contagem de testes desatualizada (dizia 457 e 128; medido 637 e 179), faixas de
  migration erradas (`V1`–`V18` e `V900`–`V903`; corretas `V1`–`V22` e `V900`–`V904`), e a instrução
  de conferir *"healthy"* no `make ps`, que não corresponde à saída em toda máquina.
- `make seed` deixa de ser um `echo "não implementado ainda"` e passa a explicar que o seed é
  migration Flyway.

### Medido
- Conservação do TOKEN por categoria, do zero: **AJUDA Δ=+30 (cunha), TRIBO Δ=0 (conserva)**, com a
  reconciliação respondendo `integro=true` nos dois casos.

---

## [F12c] — 2026-08-15 · Previsão de risco de falha de entrega

### Adicionado
- Regressão logística interpretável em Java puro, treinada dentro do `verify` sobre dataset
  **sintético** de 5.000 registros com correlações injetadas e documentadas ([ADR 0022](docs/adr/0022-previsao-de-risco-de-entrega.md)).
- `POST /api/v1/logistica/previsao-falha` — probabilidade, faixa e fatores principais.
- Multiplicador de risco **congelado** na missão, limitado a [1,00; 1,50], entrando na **base** do
  cálculo da recompensa; prioridade no fan-out de alertas.
- Resiliência das integrações externas: cache → disjuntor → bulkhead → retry, com o retry **por
  dentro** do disjuntor ([ADR 0023](docs/adr/0023-resiliencia-de-integracoes-externas.md)).

### Alterado
- **JaCoCo passa a barrar o build**: 80% de instruções global, 85% nos pacotes `dominio`.

### Corrigido
- `Math.exp` não determinístico entre JVMs → `StrictMath`; ordem de iteração randômica de `Map.of`;
  codificador duplicado entre treino e runtime; ausência de terceira partição para escolher o limiar
  (vazamento de seleção); gate JaCoCo que passava **por vácuo** com `<includes>` sem correspondência.

---

## [F8] — 2026-08-14 · Fim da entrega falida *(parcial)*

### Adicionado
- `POST /api/v1/webhooks/transportadora` — **único endpoint de escrita sem JWT**, autenticado por
  HMAC-SHA256 sobre o **corpo bruto**, idempotente por `(transportadora, código de rastreio)`
  ([ADR 0021](docs/adr/0021-verificacao-de-webhook-de-transportadora.md)).
- Ponto de custódia comercial com capacidade e ocupação sob `FOR UPDATE`; ponto lotado responde
  **200 com desfecho RECUSADA**, não erro HTTP ([ADR 0020](docs/adr/0020-ponto-de-custodia-comercial-e-proximidade-por-tribo.md)).
- Fan-out de notificação por tribo, com consentimento duplo e teto por hora.
- `tools/carrier-mock/enviar.sh` — 6 cenários contra o servidor de pé.

### Pendente
- **A carteira de patrocinador.** Sem ela, ENTREGA e AJUDA continuam cunhando token.

---

## [F12] — 2026-08-09 · App mobile completo

### Adicionado
- 11 telas mais a rota-porta, catálogo de benefícios, exportação e exclusão de conta (anonimização).

### Corrigido *(auditoria independente do mobile)*
- Prompt de permissão de localização gasto sem justificativa em tela.
- 11 de 22 pares de cor reprovando contraste WCAG AA — daí a separação entre `cores` (preenchimento)
  e `textoAcessivel` (texto).
- Conta anonimizada continuava escrevendo por até 15 minutos.

---

## [F9–F11] — 2026-08-08 · Fundação do app mobile

### Adicionado
- Sessão com access token só em memória e refresh em `expo-secure-store`; nada é persistido na web
  ([ADR 0013](docs/adr/0013-persistencia-de-segredo-por-plataforma.md)).
- Rotas protegidas, radar geoespacial, carteira, mapa por WebView + Leaflet
  ([ADR 0012](docs/adr/0012-mapa-por-webview-e-leaflet.md)).
- Catálogo de erro com **uma URI por reação de UI** ([ADR 0010](docs/adr/0010-granularidade-do-catalogo-de-tipos-de-problema.md)).

---

## [Auditoria F0→F7] — 2026-08-07/08

### Corrigido
- **Recompensa escolhida pelo cliente** — o defeito de maior impacto do projeto. Passa a ser
  calculada pelo servidor e congelada na criação, com `versao_formula`.
- Oráculo de tempo no login (~6 ms × ~68 ms) por curto-circuito de `&&`.
- `REVOKE` inerte no ledger, porque a aplicação conectava como dono das tabelas.
- `type` do RFC 9457 nunca preenchido; mojibake em 401/403/429; `/actuator/health` exigindo JWT.
- Limitadores com `ConcurrentHashMap` sem despejo — vetor de exaustão de memória — trocados por
  Caffeine com expiração.

**Cinco dos sete defeitos eram invisíveis na leitura do código.** Ver
[`docs/EVOLUCAO-ARQUITETURAL.md`](docs/EVOLUCAO-ARQUITETURAL.md).

---

## [ADR 0009] — 2026-08-07 · A premissa econômica é corrigida

### Alterado
- **Quem cria a missão não paga.** A recompensa passa a ser XP + TOKEN, calculada pelo servidor.
- **BRL sai do ciclo de missões** — `ck_missao_economia` exige `valor_brl = 0` em toda missão.
- Substitui a tabela de moedas do ADR 0004.

**Motivo:** medição contra a API mostrou o BRL do sistema subir de R$ 118 para R$ 1.618 em três
ciclos, sem que o criador pagasse nada — e com a reconciliação respondendo `integro=true` o tempo
todo, corretamente.

---

## [F7] — 2026-08-07 · Carteira e integridade transacional

### Adicionado
- Ledger **append-only**, correção por estorno; idempotência sob lock; ordem determinística de lock
  entre carteiras; outbox transacional ([ADR 0008](docs/adr/0008-ledger-append-only-e-idempotencia.md)).
- `GET /api/v1/admin/carteiras/reconciliacao`.
- Evidência de concorrência: 100 threads, deadlock cruzado, rollback.

---

## [F6] — 2026-08-07 · Geolocalização e check-in

### Adicionado
- Check-in geolocalizado validado **no servidor**, com raio por missão; radar de proximidade com
  cache; consultas geoespaciais centralizadas ([ADR 0007](docs/adr/0007-consultas-geoespaciais-centralizadas.md)).
- `EXPLAIN ANALYZE` como evidência de uso do índice GiST.

### Corrigido
- Truncamento de `Duration.toSeconds()` e estouro de `NUMERIC(10,2)` no cálculo de velocidade
  implícita — os dois achados por teste de integração, corrigidos **no código**, não na assertion.
- Deadlock de pool causado por `REQUIRES_NEW` no caminho de valor.

---

## [F5] — 2026-08-06 · Missões e ciclo de vida

### Adicionado
- Máquina de estados explícita, transição só por evento, aceite com lock pessimista
  ([ADR 0006](docs/adr/0006-maquina-estados-missao.md)).

---

## [F3–F4] — 2026-08-06 · Domínio, migrations, autenticação

### Adicionado
- Schema por Flyway com `ddl-auto: validate`; `timestamptz`, `numeric` para dinheiro,
  `geography(POINT,4326)` para coordenada; enum como `varchar` + `CHECK`.
- JWT RS256 + Argon2, refresh rotativo com detecção de reúso por família
  ([ADR 0005](docs/adr/0005-autenticacao-jwt-argon2.md)).

---

## [F2] — 2026-08-05 · Bootstrap da API

### Adicionado
- Erro em **RFC 9457** desde o início; OpenAPI; actuator em porta própria (8090).

---

## [F0–F1] — 2026-08-04 · Fundação e infraestrutura local

### Adicionado
- Monólito modular, um pacote por módulo, fronteira verificada por **ArchUnit**
  ([ADR 0001](docs/adr/0001-monolito-modular.md)).
- PostgreSQL + PostGIS em container desde o primeiro dia
  ([ADR 0002](docs/adr/0002-postgresql-postgis.md)).
