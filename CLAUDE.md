# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Omni-Tribo — Memória do Projeto

## O que é

App de missões sociais hiperlocais gamificadas. Usuários recebem missões no bairro (entregas
solidárias, coleta de recicláveis, mutirões, ajuda), fazem check-in geolocalizado e recebem XP, BRL e
tokens. Tese do produto: uma entrega que falhou vira missão comunitária remunerada.
Projeto acadêmico FIAP — Sistemas de Informação, RM 555833.

Reconstrução de um protótipo Flutter descartado. NÃO copie padrões do protótipo: lá distância e valor
eram String, não havia autenticação, e aceitar missão creditava recompensa imediatamente.

## Escopo

Desenvolvimento 100% local. Um Postgres+PostGIS em Docker, backend Spring Boot, app Expo no emulador.
NÃO adicione broker de mensageria, Redis, proxy reverso, Prometheus ou Grafana sem eu pedir — foram
deliberadamente cortados do MVP. Se achar que algum é necessário, me pergunte antes.

## Arquitetura

Monólito modular (ver docs/adr/0001). Raiz do pacote Java: `com.omnitribo` — sem prefixo `br.`.
Um pacote por módulo, `com.omnitribo.<modulo>.{api,dominio,infra}`:
compartilhado · identidade · missoes · geolocalizacao · carteira · logistica · notificacoes
Cada um com api/ (controllers, DTOs, portas), dominio/ (entidades, regras), infra/ (repositórios,
clientes). Regra verificada por ArchUnit: módulo só acessa outro por api/ pública ou evento. Nunca
repositório ou entidade JPA alheia. carteira referencia missao_id como UUID puro, sem FK,
deliberadamente.

Maturidade real por módulo (o alvo é o de cima; o de hoje é este):
- Três camadas povoadas: `compartilhado`, `identidade`, `missoes`, `carteira`, `geolocalizacao`.
  `geolocalizacao/api/` são só portas — o endpoint de check-in vive em `missoes`, porque a missão é
  o agregado que a transição pertence.
- Só `dominio/` + `infra/` — entidades e repositórios, nenhum controller: `logistica`.
- Vazio, só `.gitkeep`: `notificacoes`.

Módulo só fala com módulo por porta em `api/`. As de hoje:
- `carteira/api/` — `CreditoRecompensa`, `FinanciamentoMissao`, `EstornoPote`,
  `ProvisionamentoCarteira`
- `identidade/api/` — `ProgressaoUsuario`, `ConsultaAfiliacao`
- `geolocalizacao/api/` — `RegistroCheckin` (`missoes` injeta pela INTERFACE, porque é o tipo
  declarado no campo que o ArchUnit inspeciona — injetar a implementação passaria a compilar e
  quebraria o teste de arquitetura)
- `compartilhado/api/` — `PublicadorEventos`, `PaginaResponse`, `RecursoAuditavel`

Toda implementação roda `REQUIRED`/`MANDATORY` — **`REQUIRES_NEW` é proibido no caminho de valor**,
porque a transação externa segura `FOR UPDATE` e a interna pediria uma segunda conexão: com N ≥
tamanho do pool, deadlock de pool e 500 para todo mundo. Isso não é teoria — aconteceu no check-in da
F6 e derrubava até o login (ver Notas de manutenção de 2026-08-07).

O schema de TODOS já existe desde V4–V7: o banco está à frente do código. Encontrar tabela sem
código correspondente é o estado esperado, não resíduo. Mesma coisa fora de `services/api/`:
`tools/carrier-mock/` (logística) e `tools/seed/` (`make seed`) são diretórios reservados, hoje
vazios.

`RegrasArquiteturaTest` aplica a regra aos 6 módulos de negócio; `compartilhado` é **isento** por
ser shared por design (ver o array `MODULOS` no teste). Violação em `compartilhado` não é pega por
teste nenhum — é só disciplina.

**A regra do ArchUnit é DIRECIONAL, e isso restringe o desenho de `compartilhado`.** `compartilhado`
é isento como ALVO, mas suas classes continuam sendo ORIGEM: `ConsultasGeoespaciais` NÃO pode
importar `Missao`, `StatusMissao` nem `CategoriaMissao`. Por isso status e categoria entram como
String (sempre `.name()` de um enum já validado pelo binder, nunca texto livre do cliente) e o
retorno é `AlvoProximo`, um par neutro id+distância que o chamador reidrata.

## Economia (três moedas)

**Quem cria a missão NÃO paga.** Essa é a premissa do produto, e o ADR 0009 a registrou depois de
ela ter sido violada em silêncio pelo ADR 0004. A recompensa é XP + TOKEN, **calculada pelo servidor
e congelada na criação** — o DTO de criação NÃO tem `xpRecompensa` nem `tokensRecompensa`.

`CalculadoraDeRecompensa` (`missoes/dominio`) é função pura: recebe categoria, complexidade,
distância, peso e volume, e devolve XP + tokens + complexidade efetiva + `versaoFormula`. Calibração
em `app.missoes.recompensa.*` — a FÓRMULA é código, os NÚMEROS são configuração.

**Mudou parâmetro no YAML? Suba `versao` junto.** `CalculadoraDeRecompensaTest.douradoV1` falha de
propósito para forçar a decisão: sem isso, missões antigas passam a ser explicadas por uma calibração
que não as produziu, e some a resposta para "este crédito estava certo quando foi feito?".

**Complexidade: derivada onde há dado.** ENTREGA e COLETA exigem peso e volume, e o servidor deriva —
declarar junto é 400. TRIBO e AJUDA declaram, porque não movem objeto. A conclusão LÊ o congelado,
nunca recalcula. `POST /missoes/previa-recompensa` mostra o valor sem criar nada; o app nunca duplica
a fórmula.

XP: reputação, não transferível, monotônico, sem ledger. Nível é DERIVADO do XP por `RegraNivel`,
nunca incrementado — a coluna `usuario.nivel` é cache recalculado a cada concessão.
TOKEN: moeda comunitária, transferível na mesma tribo. **Recompensa de TODAS as categorias.**
Resgatável em benefício de parceiro do bairro — esse resgate é o sumidouro real (F8+).
BRL: **fora do ciclo de missões.** `ck_missao_economia` (V15) exige `valor_brl = 0` em toda missão, e
`app.carteira.saque-habilitado` é `false` por padrão. Colunas e `SaqueService` permanecem, testados,
como infraestrutura da conversão patrocinada futura — não remova.

Regra: nenhuma missão pode ter valor_brl > 0. Quem tentar recebe 400 apontando o campo.

Conservação do TOKEN: missão TRIBO/COLETA paga o executor a partir de `missao.pote_tokens`, que
membros financiam debitando a própria carteira. Nada é cunhado no ciclo — `SUM(carteira.saldo_tokens)
+ SUM(missao.pote_tokens)` é invariante. Publicar exige pote cobrindo a recompensa (senão a missão
chegaria em AGUARDANDO_CONFIRMACAO sem poder ser concluída); cancelar ou expirar estorna o pote aos
financiadores, senão os tokens ficam presos e a conservação vira mentira.

O estorno tem DOIS pontos de chamada, não um: `MissaoService.aplicar` e
`ExpiracaoMissoesService.expirarLote`. O job de expiração é o único caminho para EXPIRADA e não passa
por `aplicar()` — sem a chamada lá, os tokens ficariam presos numa missão morta e a reconciliação
continuaria respondendo `integro=true`, porque ledger e projeção seguem batendo. A perda seria
invisível justamente para o endpoint que existe para achá-la.

## Stack

Backend: Spring Boot 4.1 · Java 21 · Maven · PostgreSQL+PostGIS · Flyway · Caffeine · bucket4j
Mobile: Expo SDK 57 · TypeScript strict · Expo Router · TanStack Query · Zustand
Testes: JUnit 5 · Testcontainers · ArchUnit · Jest/RTL/MSW

## Comandos

> `make seed` e `make test` ainda são stubs. Os demais targets (`up`, `down`, `reset`, `logs`, `ps`, `psql`) estão implementados.

Clone novo exige UM passo antes de qualquer `./mvnw verify` ou `spring-boot:run`:

```bash
bash tools/gerar-chaves-dev.sh  # services/api/keys/ é gitignored; sem PEM nenhum contexto Spring sobe
```

O `.env` **não** é passo manual: o Makefile tem um alvo de arquivo `.env`, do qual todo target que
lê o compose depende, então `make up` o cria a partir do `.env.example` sozinho. E nada além do
compose precisa dele — `./mvnw verify` sobe o banco por Testcontainers, e `application-dev.yml` traz
defaults de datasource para o `spring-boot:run`.

```bash
# Backend
cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev  # sobe o servidor local (porta 8080; actuator na 8081)
cd services/api && ./mvnw verify                          # compila + todos os testes + spotless + spotbugs + jacoco
cd services/api && ./mvnw spotless:apply                  # corrige formatação Google Java Format (rodar antes do verify se falhar em formatação)
cd services/api && ./mvnw -Dtest=NomeDaClasseTest test    # um único teste
cd services/api && ./mvnw clean                           # OBRIGATÓRIO depois de renomear migration — ver seção Banco

# Mobile
cd apps/mobile && npm install                             # primeira vez
cd apps/mobile && npm start                               # Metro; leia o QR com o Expo Go
cd apps/mobile && npm run android                         # emulador (exige ANDROID_HOME e um AVD)
cd apps/mobile && npm run typecheck && npm run lint && npm test
cd apps/mobile && npx jest --testPathPattern=nomeDoArquivo
# Integração contra o backend EM EXECUÇÃO — fora do `npm test` de propósito:
cd apps/mobile && E2E_API_URL=http://192.168.15.6:8080 npm run test:e2e
# npx expo install <pacote>, NUNCA npm install, para pacotes do ecossistema Expo.

# Infra
make up          # sobe PostgreSQL+PostGIS
make down        # para containers (volume preservado)
make reset       # destrói volume e recria do zero (necessário ao trocar migration de nome)
make logs        # tail nos logs do banco
make ps          # status dos containers
make psql        # abre psql conectado ao banco local
# make seed / make test — ainda não implementados
```

Em dev: Swagger UI em `http://localhost:8080/swagger-ui.html`, OpenAPI em `/v3/api-docs`. Actuator
na porta **8081**, não 8080, com cadeia de segurança PRÓPRIA (`actuatorFilterChain`, `@Order(1)`):
`health` e `info` respondem anônimos — health check que exige JWT não é health check —, `metrics`
continua exigindo autenticação. Sem essa cadeia, o `anyRequest().authenticated()` da cadeia
principal alcança a porta de gestão e `/actuator/health` responde 401.

O `verify` não é só teste: SpotBugs roda com effort `Max`, threshold `Medium` e `failOnError=true`,
então achado de análise estática **quebra o build** como um teste vermelho quebraria. JaCoCo grava o
relatório de cobertura em `services/api/target/site/jacoco/`, publicado como artefato pelo CI.

## Superfície de API hoje

`/api/v1/auth` — `POST registrar` · `POST login` · `POST refresh` · `POST logout` · `GET me`

`/api/v1/missoes` — `GET` (lista paginada com filtro) · `GET /proximas` (radar geoespacial) ·
`POST /previa-recompensa` (calcula sem criar) · `POST`
· `GET /{id}` · `PATCH /{id}`, mais as ações `POST /{id}/{acao}`: `publicar`, `aceitar`, `iniciar`,
`desistir`, `cancelar`, `contestar`, `checkin`, `confirmar` e `resolver` (este último só ADMIN).
**Nenhuma ação responde 501 desde o merge de F5+F6** — o handler de `UnsupportedOperationException`
em `GlobalExceptionHandler` virou código morto, e há um comentário obsoleto em `MissaoService` (por
volta da linha 335) afirmando que `confirmar`/`resolver` ainda são stubs. Não são.

`/api/v1/carteira` — `GET` (saldo) · `GET /lancamentos` (extrato paginado) · `POST /transferencias`
· `POST /saques`. Os dois POST exigem header `Idempotency-Key`.

`/api/v1/tribos/{triboId}/financiamentos` — `POST`, com `Idempotency-Key`.

`/api/v1/admin/carteiras/reconciliacao` — `GET`, só ADMIN.

`GET /api/v1/ping`, do `PingController` em `compartilhado`.

## Automação

- Hook `PreToolUse` (`.claude/hooks/checar-segredo.sh`) bloqueia `git commit` se o diff staged tiver
  padrão de chave/senha/token — não é substituto de revisão manual, é uma segunda barreira.
- CI (`.github/workflows/`): `api.yml` gera as chaves RSA e roda `./mvnw verify` a cada push/PR que
  toque `services/api/**`, arquivando o relatório JaCoCo; `security.yml` roda Gitleaks no histórico
  completo em todo push/PR.

## Onde está o quê, na documentação

- `docs/PROGRESSO.md` — tabela de fases e **Notas de manutenção**: o log de por que cada correção
  estrutural foi feita. Primeiro lugar a olhar quando algo neste arquivo parecer arbitrário.
- `docs/auditoria/F0.md`…`F7.md` — uma auditoria por fase, contra a especificação original, com
  evidência EXECUTADA (SQL, `curl`, `EXPLAIN ANALYZE`). Classificam cada item como DEFEITO, LACUNA,
  DIVERGÊNCIA ACEITÁVEL, EXCEDENTE ou CONFORME. É onde está o raciocínio por trás de decisões que
  parecem estranhas — inclusive duas premissas de especificação que foram refutadas com medição.
- `docs/adr/` — decisões com alternativas descartadas. 0001 monólito · 0002 PostGIS · 0003 Expo ·
  0004 três moedas (**tabela de moedas substituída pelo 0009**) · 0005 JWT+Argon2 · 0006 máquina de
  estados · 0007 consultas geoespaciais centralizadas · 0008 ledger append-only e idempotência ·
  **0009 economia do cuidado: TOKEN como recompensa, BRL fora do ciclo** · **0010 granularidade do
  catálogo de tipos de problema: uma URI por REAÇÃO DE UI**.
- `docs/qualidade/integridade-transacional.md` — evidência de concorrência da carteira (100 threads,
  deadlock, rollback) e a seção "O que esta fase NÃO garante". É o documento a defender oralmente.
- `docs/seguranca/autenticacao.md` — modelo de ameaça e desenho do fluxo de auth.
- `docs/seguranca/antifraude-geolocalizacao.md` — o que os controles de check-in **não** pegam.
- `docs/evidencias/f6-explain-analyze.md` — saída real do `EXPLAIN ANALYZE` provando uso do índice
  GiST, gerada por `IndiceGeoespacialTest`.
- `docs/INFRA.md` — containers, credenciais de dev, lista completa de usuários seed com tribo.
- `docs/qualidade/` — evidência de build por data. `docs/diagramas/` vazio.
- `CONTRIBUTING.md` — tabela de tipos de Conventional Commit aceitos e checklist pré-commit.

## Skills e agentes disponíveis

- `/verificar` — roda verificação completa (mvnw verify + typecheck + lint + test + docker compose ps) e reporta verde/vermelho. Use antes de abrir PR. **Nunca declare sucesso sem rodar isso.**
  O passo 2 (mobile) reporta NÃO VERIFICADO enquanto `apps/mobile/package.json` não existir (F9+);
  isso não é falha e não invalida os passos 1, 3 e 4.
- `/adr <assunto>` — cria `docs/adr/NNNN-<slug>.md` com o próximo número. Template exige Alternativas descartadas com motivo real.
- Agente `auditor` — audita uma fase contra a especificação e entrega relatório em
  `docs/auditoria/FN.md`. **Não altera arquivo do projeto.** Regra central: medir antes de afirmar.
- Agente `revisor-seguranca` — revisa autenticação, autorização, endpoints de valor, webhooks, dados pessoais. Checar após implementar qualquer um desses.
- Agente `revisor-testes` — avalia se a suíte realmente garante comportamento (não conta testes, avalia o que cobrem). Rodar ao fechar fase.

## Convenções por camada

**Backend** (`services/api/`):
- DTOs são `record`. Entidade JPA nunca cruza fronteira do controller.
- Exceções de domínio herdam de `DominioException` → mapeadas para status HTTP no handler global → resposta RFC 9457 `ProblemDetail`.
- Identidade do usuário logado no controller: injete `@AuthenticationPrincipal AutenticadoPrincipal principal` — nunca extraia do corpo ou query string.
- Teste de integração: use `TesteIntegracaoBase` (RANDOM_PORT + TestRestTemplate) para roundtrip HTTP real; use `TesteIntegracaoMvcBase` (WebEnvironment.MOCK + MockMvc) quando precisar inspecionar headers de resposta. Ambas estendem `ContainerConfig` (PostgreSQL+PostGIS singleton). Spring Boot 4.1 removeu `@AutoConfigureMockMvc` — não tente usá-lo.
- **MockMvc não herda filtro de servlet auto-registrado por `@Component`** — monta só a cadeia do
  Spring Security. Por isso o `CorrelationIdFilter` é adicionado à mão em `MockMvcTestConfig`, e por
  isso o `RateLimitFilter` funciona sem gambiarra (entra pela cadeia, via `addFilterBefore` no
  `SecurityConfig`). Filtro novo exige decidir por qual dos dois caminhos ele entra; esquecer disso
  não quebra teste nenhum, só faz a suíte exercitar uma cadeia diferente da que roda em produção.
- **Toda função PostGIS vive numa ÚNICA classe: `compartilhado/infra/ConsultasGeoespaciais`.** Nenhum
  `ST_*` existe fora dela. Substitui a regra "um `*GeoRepository` por módulo" do ADR 0002, que não
  sobreviveu à segunda consulta — as duas consultas geoespaciais estão em módulos diferentes e a
  regra ArchUnit é direcional, então `ST_*` acabaria em dois arquivos. Ver **ADR 0007**; os stubs
  `CheckinGeoRepository` e `PontoCustodiaGeoRepository` foram apagados. Usa `JdbcClient`, não
  `@Query(nativeQuery=true)`, que exigiria interface ligada a uma `@Entity` — e a única entidade
  visível de `compartilhado` seria `Outbox`. Parâmetros nomeados e zero concatenação continuam
  obrigatórios. Query nativa NÃO geoespacial continua em `infra/` do próprio módulo.
- `CAST(:param AS ...)` nas queries nativas não é decoração: um parâmetro nulo sem tipo chega ao
  PostgreSQL como `bytea` e a consulta estoura com "function ... does not exist". Ver
  `MissaoRepository.buscarComFiltros` e `ConsultasGeoespaciais`.
- Escrita de domínio auditável tem DUAS metades, e faltar uma não quebra nada em tempo de compilação:
  o método de serviço leva `@Auditavel(acao=..., entidade=...)`, e o DTO de resposta que ele devolve
  implementa `RecursoAuditavel.idAuditoria()`. Sem a anotação o `AuditoriaAspecto` é advice que nunca
  dispara; sem a interface ele grava `entidade_id` nulo e a trilha vira "alguém publicou uma missão"
  sem dizer QUAL — inútil para reconstruir incidente. Padrão a copiar: os métodos anotados em
  `MissaoService` + `MissaoResponse implements RecursoAuditavel`. Eventos de autenticação são a
  exceção deliberada: gravados à mão no `AutenticacaoService`, porque precisam de `atorId` nulo.
- `fail-on-unknown-properties: false` (em `application.yml`) é decisão de segurança, não default
  frouxo: o DTO de request declara só o que pode mudar, então mandar `status`, `executorId` ou
  `xpRecompensa` num PATCH é silenciosamente ignorado. É a proteção contra mass assignment — não
  "endureça" isso para 400 achando que melhora.
- Spotless (Google Java Format) é verificado no `verify`. Se falhar por formatação, rode `./mvnw spotless:apply`.
- Jackson é **3** (`tools.jackson`) em todo o repositório, main e test. Spring Boot 4.1 autoconfigura
  esse mapper e não existe bean de `com.fasterxml.jackson.databind.ObjectMapper` — injetá-lo impede
  o contexto de subir. Quem precisa serializar constrói o próprio `JsonMapper`, sem injeção: veja
  `MissaoService.MAPPER_TRILHA` no main e `TesteIntegracaoMvcBase.JSON` nos testes. Em teste novo,
  use `JSON` — não declare bean de mapper.
- AOP: `spring-boot-starter-aop` não existe no Boot 4.x. O suporte a `@Aspect` vem de
  `aspectjweaver` declarado direto no `pom.xml`.
- Mudança de status de missão passa SEMPRE por `MissaoStateMachine`. Nunca chame `missao.setStatus(...)` fora dela.
- Mudança de SALDO passa SEMPRE por `LivroRazaoService`. Nunca chame `carteira.creditar/debitar(...)`
  fora dele: o ledger e a projeção têm de ser escritos na mesma transação, ou divergem em silêncio.
- **Toda operação de valor segue esta ordem, sem exceção: adquira todos os locks → sonde a chave de
  idempotência → valide as regras → escreva.** É o lock que fecha a corrida entre sondar e inserir,
  não a sondagem. Ordem global dos locks: `missao` → `carteira` (id CRESCENTE) → `usuario`; travar
  duas carteiras fora de ordem crescente reabre o deadlock A→B / B→A.
- Nada captura `DataIntegrityViolationException` para tratar replay. O no-op é decidido pela sondagem
  sob lock; se `uk_lancamento_idempotencia` disparar é defeito, e sobe como 500. Capturar dentro da
  transação já a marcou rollback-only, então o "tratamento" produziria um commit impossível.
- **Armadilha da primeira leitura, em três entidades.** Se `Carteira` (ou `Missao`, ou `Usuario`) já
  estiver no persistence context, o Hibernate devolve a instância em cache SEM reemitir o
  `SELECT ... FOR UPDATE` — o teste passa e o lock nunca existiu. Por isso resolver
  `usuarioId → carteiraId` usa a projeção escalar `buscarIdPorUsuario`, nunca `findByUsuarioId`, e
  por isso `buscarParaAtualizar(missaoId)` é sempre a PRIMEIRA leitura da transação (inclusive antes
  da checagem de rascunho alheio, em `registrarCheckin`).
- **Todo corpo de erro sai com `type` do catálogo `TipoProblema`, nunca `about:blank`.** O `type` é
  o que o app usa para decidir comportamento; status HTTP sozinho é ambíguo (dois 409 diferentes
  pedem reações diferentes) e `detail` é texto para humano, que muda a cada revisão de copy. Há
  TRÊS caminhos que produzem erro e os três precisam concordar: o `GlobalExceptionHandler`, os ~15
  handlers herdados do `ResponseEntityExceptionHandler` (cobertos pelo override de
  `createResponseEntity`) e os escritores manuais de JSON em `SecurityConfig` e `RateLimitFilter`,
  que rodam na cadeia de filtros, antes do DispatcherServlet. Esquecer o terceiro é o erro fácil:
  401 e 429 são justamente os que o app mais recebe.
- **Quem escreve JSON à mão num filtro precisa de `setCharacterEncoding(UTF_8)` explícito.**
  `setContentType("application/problem+json")` não define charset, o servlet cai em ISO-8859-1 e
  "Autenticação necessária" chega ao cliente como Latin-1 rotulado de JSON — que é UTF-8 por
  definição (RFC 8259 §8.1). Não aparece em teste que só olha status code.
- Erro de regra de negócio no servidor é `RegraNegocioVioladaException` → **422**, não 409. O 409 diz
  "não cabe neste estado, caberia em outro"; o 422 diz "cabe no estado, mas os dados não satisfazem".
  A ordem de checagem é 403 → 409 → 422: inverter 409 e 422 quebra o contrato que o app já integra.
  **O check-in insere a sondagem de idempotência no meio: 403 → sondagem → 409 → gravação.** Fica
  depois do 403 porque antes dele um não-executor receberia dados da missão; e antes do 409 porque um
  replay legítimo chega com a missão já em `AGUARDANDO_CONFIRMACAO` e levaria 409. É por isso que
  `MissaoStateMachine.validarAutorizacao` é pública.
- **Recusa de regra que precisa ser GRAVADA volta como VALOR, não como exceção.** Lançar de dentro da
  transação apagaria a linha no rollback; usar `REQUIRES_NEW` para preservá-la trava a aplicação
  inteira (ver Arquitetura). O serviço devolve um resultado (`ResultadoRegistroCheckin`,
  `ResultadoCheckin`), a transação commita nos dois casos, e o **controller** lança o 422 depois do
  commit. Padrão a copiar em qualquer trilha antifraude/auditoria futura.
- Evento de domínio vai para a outbox por `PublicadorEventos`, na mesma transação do fato. Nunca
  notifique direto: antes do commit você anuncia o que o rollback desfaz; depois, perde o que falhar.
  O `DrenadorOutboxJob` drena com `SKIP LOCKED` e backoff exponencial (30s, 1min, 2min, 4min, 8min;
  `maximo-tentativas: 5`), e o `DespachanteAlerta` grava uma linha em `alerta` — destino provisório
  até o push real do mobile. Garantia é **at-least-once**: o consumidor tolera duplicata.
- Cache de proximidade (`CacheMissoesProximas`, Caffeine, TTL 30s, chave por geohash de precisão 7 +
  raio + categoria + limite) é invalidado **depois do commit**, via
  `TransactionSynchronization.afterCommit` — invalidar dentro da transação deixaria uma leitura
  concorrente repopular com estado pré-commit, e a entrada obsoleta sobreviveria o TTL inteiro. Por
  isso NÃO se usa `spring-boot-starter-cache`/`@CacheEvict`, que dispara dentro da transação. São
  **cinco** pontos de invalidação, não dois: `criar`, `atualizar`, `aplicar`, `registrarCheckin` e
  `expirarLote` — este último chama a máquina de estados direto, sem passar por `aplicar`.
- Ao escrever teste, saiba o que `application-test.yml` desliga de propósito — três coisas, todas
  para não mascarar o que o teste mede:
  - rate limit de leitura/escrita em 10000/min: um teste de rate limit precisa sobrescrever o valor;
  - `app.agendamento.habilitado: false`: o job de expiração não roda, para não mudar status entre
    arrange e assert. A regra é testada chamando `expirarLote()` direto;
  - pool Hikari em **40**, dimensionado pelo teste mais pesado (`ConclusaoConcorrenteTest`, 100
    threads). Não é 100 porque as threads serializam atrás de um único `FOR UPDATE` na missão; 40 é
    margem para runner de CI lento não virar `SQLTransientConnectionException` — que apareceria como
    500 e seria lido como bug de concorrência em vez do problema de infra que é.
- Para autenticar em teste sem passar pelo `/auth/login`, use `JwtTestConfig.gerarTokenValido(...)`
  e `gerarTokenExpirado(...)`. Login real esbarra no bloqueio de 5 tentativas/min, e um teste com
  muitos usuários falharia por 429 em vez de pela regra em avaliação. Fixtures: `MissaoFixture` e
  `SuporteCarteira`.
- O `@Primary` do `JwtTestConfig` **não** impede o `JwtService` real de ser instanciado — `@Primary`
  só desempata injeção quando há mais de um candidato. O `@PostConstruct` do bean real roda de
  qualquer jeito e lê os PEM do disco, e é por isso que as chaves são obrigatórias mesmo para rodar
  só testes, e que `api.yml` tem um passo `gerar-chaves-dev.sh` antes do `verify`. Remover esse
  passo do CI derruba TODA a suíte de integração no GitHub enquanto tudo continua verde local.

**Mobile** (`apps/mobile/`): F9–F11 implementadas. As convenções vivem em `apps/mobile/CLAUDE.md`,
que entra em contexto ao mexer lá — não duplicadas aqui. Três armadilhas do ambiente de teste, que
custaram tempo e não aparecem em lugar nenhum da documentação do Expo:
- **jest-expo 57 fixa o ecossistema jest 29.** Instalar o `jest` 30 (que é o `latest` do npm) mistura
  `jest-runtime` 30 com `jest-environment-node` 29 e a suíte morre em
  `this._moduleMocker.clearMocksOnScope is not a function` — erro que não menciona versão nenhuma.
- **RNTL 14 tornou `render` e `fireEvent` ASSÍNCRONOS.** Sem `await`, `screen` fica vazio e todo
  `getByTestId` estoura com "`render` function has not been called".
- **O ambiente do jest-expo não faz rede de verdade** — o `XMLHttpRequest` e o `fetch` dele são
  dublês. Por isso o teste de integração roda em `testEnvironment: 'node'`
  (`jest.e2e.config.js`), com stub de `react-native`; sob o preset do RN toda chamada volta como
  `semRede`, indistinguível de backend desligado.

## Regras não negociáveis

Versões

- NUNCA escreva número de versão de memória. Verifique no Maven Central, npm ou start.spring.io.
  Se não conseguir verificar, pare e pergunte.
- No mobile use `npx expo install`, nunca `npm install` direto, para pacotes do ecossistema Expo.

Banco

- Flyway é a ÚNICA fonte de schema. ddl-auto é sempre validate. Nunca resolva divergência mudando
  ddl-auto — escreva migration.
- **Versão de migration é sequência GLOBAL, não por diretório.** Duas faixas, separadas de propósito:
  - `db/migration` — schema, **V1–V8 e V11–V17**; único location do perfil default/prod.
    Próxima é **V18**. **V9 e V10 estão queimadas — nunca as reutilize.** Foram os arquivos de seed
    antes da renomeação para `V900__seed_dev.sql`, então um banco de dev criado antes dela tem as
    versões 9 e 10 gravadas no `flyway_schema_history` com descrição de seed. Um `V9__*.sql` novo em
    `db/migration` passaria em clone novo e falharia em máquina antiga com erro de checksum ou
    "detected applied migration not resolved locally" — divergência que não aparece no CI.
  - `db/seed` — só dev e test (via `application-dev.yml` / `application-test.yml`), faixa **900+**.
  - A faixa 900+ garante por construção que o seed roda depois de todo schema. Seed novo usa
    V901, V902… e NUNCA um número que o schema possa alcançar. Ver ADR 0006, Notas de manutenção.
  - Como o seed é o último, ele grava dados em forma final: não conte com migration posterior para
    corrigir valor de seed.
  - **Fases em paralelo devem reservar faixas disjuntas.** F5 pulou de V11 para V13 exatamente para
    não colidir com a V12 da branch de geolocalização — duas migrations com a mesma versão derrubam
    o merge com *"more than one migration with version N"*. Antes de escolher o número, olhe as
    branches abertas, não só `db/migration` local.
  - **Consequência de ter o seed em V900: toda migration nova exige `make reset` num banco de dev já
    existente.** Como V900 já está aplicada, qualquer V12/V13/V15 nova tem versão MENOR que o topo do
    histórico e o Flyway a classifica como *out-of-order* — que `application-dev.yml` mantém
    desligado. O sintoma é o `spring-boot:run` morrer no boot com `Validate failed: Detected resolved
    migration not applied to database: 12`, sem nenhuma menção a seed ou a ordenação. Não tente
    resolver com `out-of-order: true`: isso deixaria o schema de dev divergir da ordem que prod
    aplicaria. `make reset` é a resposta, e o custo é zero porque o seed reconstrói os dados.
  - **Ao RENOMEAR uma migration, rode `./mvnw clean`** (além do `make reset`). O Maven não remove de
    `target/classes` o arquivo com o nome antigo, então o Flyway acha os dois e aplica os dois — o
    sintoma é `duplicate key value violates unique constraint`, que não parece ter relação nenhuma
    com renomear arquivo. CI não sofre disso: clona do zero.
- Dinheiro: numeric(12,2) → BigDecimal. Tokens: bigint. Nunca double, nunca String.
- Coordenada: geography(POINT,4326). Distância é derivada por PostGIS, nunca armazenada. `ST_DWithin`
  sobre `geography` recebe o raio em METROS e `ST_Distance` devolve METROS — nenhuma conversão de
  unidade acontece em Java.
- Extensões `postgis` e `pgcrypto` são habilitadas via `docker/init/01-extensions.sql` e `V1__extensoes.sql`. `pgcrypto` provê `gen_random_uuid()` no banco.
- timestamptz, nunca timestamp. Enum: varchar + CHECK + EnumType.STRING, nunca ordinal.
- lancamento, auditoria e checkin são APPEND-ONLY. Correção por ESTORNO, nunca UPDATE.

Segurança

- Nenhum segredo em arquivo versionado. Só ${VARIAVEL}, com .env.example commitado.
- Identidade do usuário vem SEMPRE do JWT. Nunca do corpo, query ou header.
- Controller nunca recebe nem devolve entidade JPA. Sempre DTO/record.
- SQL sempre com parâmetro bindado, inclusive nas queries PostGIS. Zero concatenação.
- Erro é RFC 9457 ProblemDetail. Nunca stack trace, SQL, nome de classe ou mensagem de driver.
- Nunca logue senha, token, refresh, coordenada exata ou payload de requisição autenticada.
- Mobile: credencial em expo-secure-store. NUNCA AsyncStorage.
- Validação geoespacial e de saldo é SEMPRE no servidor. Valor calculado no cliente é ignorado.
- **Chave de idempotência do cliente nunca é armazenada crua quando a UNIQUE é global.** O check-in
  guarda `sha256(usuario|missao|chave_do_cliente)`: com a chave crua, o cliente que manda `"1"`
  receberia o replay do check-in alheio. Ver `ChaveIdempotencia`.
- HMAC de webhook é sobre o CORPO BRUTO, não o objeto desserializado, comparado em tempo constante.
- Deep link é entrada não confiável: valide esquema, host e formato antes de navegar.
- Transferência entre carteiras trava as duas em ordem determinística (ordene por id da carteira),
  sob pena de deadlock.

Testes

- Todo endpoint novo nasce com teste de caminho feliz e de erro. Fase sem teste verde não está pronta.
- Integração usa Testcontainers com PostGIS real. Nunca H2 para geoespacial.
- Operação de valor (aceite, crédito, transferência, saque, check-in) exige teste de concorrência
  multi-thread.
- Não escreva teste sem assertion para subir cobertura.
- Quando um teste de integração acusa bug, corrija o CÓDIGO. Os dois bugs de cinemática da F6
  (truncamento de `Duration.toSeconds()` e estouro de `NUMERIC(10,2)`) foram achados assim e
  corrigidos no cálculo, não na assertion.

Git

- Conventional Commits. Uma branch por fase: feat/f6-geolocalizacao. Nunca commite na main direto.
- NUNCA git push --force nem git reset --hard sem eu pedir explicitamente.
- Antes de commitar, confira que não há segredo no diff.
- **Merge de duas branches de fase que tocam o mesmo serviço exige `./mvnw verify` depois do merge,
  antes do push.** Resolver conflito em construtor de serviço é onde isso quebra: as duas versões
  compilam isoladas, e o resultado do merge pode manter os corpos de métodos de um lado e o
  construtor do outro. Foi o que aconteceu em `develop` (ver Pendências conhecidas).

## Como trabalhar comigo

- Tarefa não trivial: planeje primeiro, mostre o plano, espere aprovação.
- Não diga "pronto" sem ter EXECUTADO o comando de verificação e colado a saída real.
  Compilar não é testar. Teste passando não é feature funcionando.
- Se um teste falhar, não relaxe a assertion nem adicione @Disabled. Corrija o código ou me explique
  por que a expectativa estava errada.
- Se meu pedido é ambíguo, contradiz este arquivo, ou você acha a abordagem ruim: diga antes de codar.
- Comente o PORQUÊ, não o quê — especialmente em segurança e concorrência. Preciso poder defender
  esse código oralmente numa banca.
- Português nos nomes de domínio (Missao, Carteira, StatusMissao) e nas mensagens ao usuário.
  Inglês nos termos técnicos consagrados (Repository, Service, Controller, Dto).
- Decisão arquitetural relevante gera ADR em docs/adr/.

### Modo auditoria

Quando eu pedir para AUDITAR uma fase, o modo é outro: **não altere nenhum arquivo do projeto.** A
entrega é um relatório em `docs/auditoria/FN.md`, e só ele.

- Classifique cada item como **DEFEITO**, **LACUNA**, **DIVERGÊNCIA ACEITÁVEL**, **EXCEDENTE** ou
  **CONFORME**, sempre com arquivo e linha.
- **Meça antes de afirmar.** Rode SQL contra o banco de pé, `curl` contra a API em execução, e os
  testes. Vários achados das auditorias F0–F7 eram invisíveis na leitura do código: o oráculo de
  tempo no login (~6 ms contra ~68 ms), o `REVOKE` inerte porque a aplicação conecta como dono das
  tabelas, e o comentário que afirmava uma defesa inexistente. Ler o código teria confirmado o
  comentário.
- Se um item da especificação estiver tecnicamente errado, **diga**, com o raciocínio — não acomode.
  Ex.: "404 vaza existência" está invertido; quem vaza é o 403.
- Termine com ordem de correção por impacto, e PARE. Corrigir é tarefa separada, e eu decido quando.

## Estado atual

**Backend fechado até F7, auditado fase a fase.** Build verde com **383 testes**, 0 falhas, SpotBugs
limpo. Os oito relatórios em `docs/auditoria/` são o registro do que foi verificado e do que ficou
em aberto; a rodada corrigiu 7 defeitos, cinco deles invisíveis na leitura do código.

**Mobile: F9, F10 e F11 implementadas** em `apps/mobile/` (Expo SDK 57). Design system em
`src/theme` + `src/components`, cliente HTTP com rotação única de refresh, sessão com access token
só em memória e refresh em `expo-secure-store`, rotas `(auth)`/`(tabs)` protegidas, lista de missões
com radar geoespacial e paginação infinita, detalhe com o ciclo de vida e check-in, carteira com
saldo em TOKEN e extrato. Suíte com Jest/RTL/MSW, mais um teste de integração contra o backend em
execução (`npm run test:e2e`), fora do `npm test`.

O catálogo de erro foi ampliado antes da primeira tela, como a antiga Pendência #4 exigia — ver
**ADR 0010**. Ficam de fora, e o motivo está na Pendência #3: as quatro leituras que o backend ainda
não expõe. A mais visível é `GET /auth/me`, que devolve só `{id, email, papel}` — por isso a tela de
perfil é mínima e não mostra nome, tribo, XP nem nível.

`develop` carrega o merge de duas fases (carteira e geolocalização) que chegou quebrado — construtor
de uma branch com corpos de método da outra — e foi consertado na auditoria de 2026-08-07.

Módulo `missoes`: máquina de estados em `StatusMissao` + `MissaoStateMachine` (9 estados, **13**
transições — ver ADR 0006), endpoints em `/api/v1/missoes`, aceite com lock pessimista, radar de
proximidade com cache, expiração por `@Scheduled`. **Recompensa derivada por
`CalculadoraDeRecompensa` e congelada com `versao_formula`** — o cliente não a informa (V16).

Módulo `geolocalizacao`: check-in geolocalizado com validação 100% servidor, idempotência por hash,
trilha antifraude append-only (a rejeição é gravada E o 422 é devolvido). O que os controles não
pegam está em `docs/seguranca/antifraude-geolocalizacao.md`: spoofing com root/emulador é mitigável e
não eliminável, `mocked` é reportado pelo cliente, presença não é execução, conluio não é detectado,
e a cinemática é cega no primeiro check-in de cada conta.

Módulo `carteira`: ledger append-only, conclusão de missão creditando na mesma transação,
transferência P2P com ordem determinística de lock, saque, extrato, financiamento com pote e
reconciliação admin. Outbox transacional drenada por `@Scheduled`. Decisões no ADR 0008; a evidência
de concorrência (100 threads, deadlock, rollback) está em `docs/qualidade/integridade-transacional.md`
e é o documento a defender oralmente.

`CONCLUIDA` continua sendo o ÚNICO estado que credita — a regra que o protótipo descartado violava.

Módulos `logistica` e `notificacoes` são F8: o primeiro tem entidades e repositórios sem serviço nem
controller (`PontoCustodiaRepository` é órfão hoje), o segundo está vazio. Quando `notificacoes` for
povoado, `DespachanteAlerta` migra de `compartilhado/dominio` para lá — e passa a valer para ele a
regra do ArchUnit, que hoje não o alcança porque `compartilhado` é isento.

Contagem de testes e evidência de build NÃO ficam neste arquivo — envelhecem a cada PR. Fonte:
docs/PROGRESSO.md e docs/qualidade/.

Usuários seed (perfis `dev` e `test`, carregados via `db/seed/V900__seed_dev.sql`, senha `Senha@123`):
`admin@omnitribo.dev` (ADMIN) · `alice` e `carol` (USUARIO, Tribo Pinheiros e Vila Madalena) · `bob`, `diana`, `erik` (USUARIO). Ver docs/INFRA.md para lista completa com tribos.

## Pendências conhecidas

Seção para armadilhas diagnosticadas e ainda não corrigidas. Ao resolver uma, remova-a daqui.

**1. O `REVOKE UPDATE, DELETE` das tabelas append-only não vale em runtime.** As migrations criam o
papel `omnitribo_app` com `SELECT, INSERT` apenas em `lancamento`, `auditoria`, `checkin` e
`missao_evento`, e o comentário SQL descreve isso como "defesa em profundidade... mesmo que o código
da aplicação tente executá-los". **Mas a aplicação não conecta com esse papel** — `application.yml`,
`application-dev.yml` e `application-test.yml` usam `omnitribo`, dono das tabelas, para quem GRANT e
REVOKE não se aplicam. Verificado: como `omnitribo_app` o `UPDATE lancamento` responde
`permission denied`; como `omnitribo` ele altera todas as linhas.

O papel está correto e `MigracaoTest` agora trava a matriz de privilégios, então a metade que existe
não regride em silêncio. Falta a outra metade: apontar o datasource para `omnitribo_app` e dar ao
Flyway um usuário próprio com DDL (`spring.flyway.user`), já que o papel de aplicação não pode criar
schema. Enquanto isso não for feito, a imutabilidade do ledger é garantida só pela disciplina do
código — que é o que o ADR 0008 já argumenta —, e não pelo banco.

**2. ENTREGA e AJUDA ainda CUNHAM token, até a carteira de patrocinador da F8.** `pagaTokensDoPote`
cobre só TRIBO e COLETA, então a conservação
`SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)` vale para essas duas, não para o sistema
inteiro.

**Isto não foi contornado de propósito, e a razão importa.** Exigir pote para ENTREGA hoje faria
membros da tribo custearem a logística do varejista — o inverso do modelo. O financiador correto
dessas categorias é o PATROCINADOR: entrega que falhou custa re-entrega, armazenagem e risco de
perder o cliente, então patrocinar o pote sai mais barato que o fracasso. É esse o caso de negócio
do challenge. Preferimos uma lacuna documentada a uma regra errada codificada. Fecha na F8, quando a
carteira de patrocinador financiar o pote pela mecânica que já existe (`FinanciamentoMissao`), e aí
`pagaTokensDoPote` passa a valer para todas as categorias.

**3. Quatro leituras que o app mobile vai pedir e ainda não existem.** Nenhuma bloqueia começar o
front — o núcleo (auth, radar, ciclo de vida, check-in, carteira, extrato) está completo e foi
exercitado ponta a ponta. Cada uma é um `GET` sem regra de negócio nova, e deve entrar quando a tela
que a consome existir, não antes:

| Falta | Tela afetada | Situação hoje |
|---|---|---|
| `GET /alertas` | notificações | `DespachanteAlerta` grava em `alerta`, e ninguém lê — caixa de entrada invisível |
| `GET /auth/me` completo | perfil | devolve só `{id, email, papel}`; sem nome, handle, XP, nível, tribo |
| `GET /tribos` | perfil, registro | "sua tribo" só existe como UUID |
| `GET /pontos-custodia/{id}` | detalhe de ENTREGA | `MissaoResponse` traz `pontoCustodiaId` cru; o app mostraria um UUID em vez de "Leroy Merlin Pinheiros". `PontoCustodiaRepository` é repositório órfão, sem serviço nem controller |

**4. As telas do app que dependem dessas quatro leituras estão incompletas por consequência, não por
descuido.** Perfil mostra e-mail, papel e id, e diz na própria tela que o resto não vem da API; o
detalhe de ENTREGA não exibe o ponto de custódia; não há caixa de alertas; e o registro não deixa
escolher tribo, porque escolher sem poder listar seria digitar um UUID. Fecham junto com a #3.
