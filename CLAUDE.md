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

Monólito modular (ver docs/adr/0001). Módulos:
compartilhado · identidade · missoes · geolocalizacao · carteira · logistica · notificacoes
Cada um com api/ (controllers, DTOs), dominio/ (entidades, regras), infra/ (repositórios, clientes).
Regra verificada por ArchUnit: módulo só acessa outro por api/ pública ou evento. Nunca repositório
ou entidade JPA alheia. carteira referencia missao_id como UUID puro, sem FK, deliberadamente.

Maturidade real por módulo (o alvo é o de cima; o de hoje é este):
- Três camadas completas: `compartilhado`, `identidade`, `missoes`, `carteira`.
- Só `dominio/` + `infra/` — entidades e repositórios, nenhum controller: `geolocalizacao`,
  `logistica`.
- Vazio, só `.gitkeep`: `notificacoes`.

Módulo só fala com módulo por porta em `api/`. As de hoje: `carteira/api/` expõe `CreditoRecompensa`,
`FinanciamentoMissao`, `EstornoPote` e `ProvisionamentoCarteira`; `identidade/api/` expõe
`ProgressaoUsuario` e `ConsultaAfiliacao`; `compartilhado/api/` expõe `PublicadorEventos`. Toda
implementação roda `REQUIRED`/`MANDATORY` — **`REQUIRES_NEW` é proibido no caminho de valor**, porque
a transação externa segura `FOR UPDATE` e a interna pediria uma segunda conexão: com N ≥ tamanho do
pool, deadlock de pool e 500 para todo mundo.

O schema de TODOS já existe desde V4–V7: o banco está à frente do código. Encontrar tabela sem
código correspondente é o estado esperado, não resíduo. Mesma coisa fora de `services/api/`:
`tools/carrier-mock/` (F7) e `tools/seed/` (`make seed`) são diretórios reservados, hoje vazios.

`RegrasArquiteturaTest` aplica a regra aos 6 módulos de negócio; `compartilhado` é **isento** por
ser shared por design (ver o array `MODULOS` no teste). Violação em `compartilhado` não é pega por
teste nenhum — é só disciplina.

## Economia (três moedas)

XP: reputação, não transferível, monotônico, sem ledger. Nível é DERIVADO do XP por `RegraNivel`,
nunca incrementado — a coluna `usuario.nivel` é cache recalculado a cada concessão.
BRL: dinheiro real, ACID rigoroso. Missões ENTREGA e AJUDA.
TOKEN: moeda comunitária, transferível na mesma tribo. Missões TRIBO e COLETA.
Regra: missão TRIBO ou COLETA não pode ter valor_brl > 0.

Conservação do TOKEN: missão TRIBO/COLETA paga o executor a partir de `missao.pote_tokens`, que
membros financiam debitando a própria carteira. Nada é cunhado no ciclo — `SUM(carteira.saldo_tokens)
+ SUM(missao.pote_tokens)` é invariante. Publicar exige pote cobrindo a recompensa (senão a missão
chegaria em AGUARDANDO_CONFIRMACAO sem poder ser concluída); cancelar ou expirar estorna o pote aos
financiadores, senão os tokens ficam presos e a conservação vira mentira.

## Stack

Backend: Spring Boot 4.1 · Java 21 · Maven · PostgreSQL+PostGIS · Flyway
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

# Mobile — F9+, NENHUM destes comandos existe ainda.
# apps/mobile/ contém só .gitkeep e CLAUDE.md; não há package.json.
# cd apps/mobile && npm run android
# cd apps/mobile && npm run typecheck && npm run lint && npm test
# cd apps/mobile && npx jest --testPathPattern=nomeDoArquivo

# Infra
make up          # sobe PostgreSQL+PostGIS
make down        # para containers (volume preservado)
make reset       # destrói volume e recria do zero (necessário ao trocar migration de nome)
make logs        # tail nos logs do banco
make ps          # status dos containers
make psql        # abre psql conectado ao banco local
# make seed / make test — ainda não implementados
```

Em dev: Swagger UI em `http://localhost:8080/swagger-ui.html`, OpenAPI em `/v3/api-docs`. Actuator (health, info, metrics) na porta **8081**, não 8080.

O `verify` não é só teste: SpotBugs roda com effort `Max`, threshold `Medium` e `failOnError=true`,
então achado de análise estática **quebra o build** como um teste vermelho quebraria. JaCoCo grava o
relatório de cobertura em `services/api/target/site/jacoco/`, publicado como artefato pelo CI.

## Superfície de API hoje

`/api/v1/auth` — `POST registrar` · `POST login` · `POST refresh` · `POST logout` · `GET me`

`/api/v1/missoes` — `GET` (lista paginada com filtro) · `POST` · `GET /{id}` · `PATCH /{id}`, mais
as ações `POST /{id}/{acao}`: `publicar`, `aceitar`, `iniciar`, `desistir`, `cancelar`, `contestar`,
`confirmar` e `resolver`. Só `checkin` ainda responde 501, até F6.

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
- `docs/adr/` — decisões com alternativas descartadas. 0001 monólito · 0002 PostGIS · 0003 Expo ·
  0004 três moedas · 0005 JWT+Argon2 · 0006 máquina de estados.
- `docs/seguranca/autenticacao.md` — modelo de ameaça e desenho do fluxo de auth.
- `docs/INFRA.md` — containers, credenciais de dev, lista completa de usuários seed com tribo.
- `docs/qualidade/` — evidência de build por data. `docs/diagramas/` e `docs/evidencias/` vazios.
- `CONTRIBUTING.md` — tabela de tipos de Conventional Commit aceitos e checklist pré-commit.

## Skills e agentes disponíveis

- `/verificar` — roda verificação completa (mvnw verify + typecheck + lint + test + docker compose ps) e reporta verde/vermelho. Use antes de abrir PR. **Nunca declare sucesso sem rodar isso.**
  O passo 2 (mobile) reporta NÃO VERIFICADO enquanto `apps/mobile/package.json` não existir (F9+);
  isso não é falha e não invalida os passos 1, 3 e 4.
- `/adr <assunto>` — cria `docs/adr/NNNN-<slug>.md` com o próximo número. Template exige Alternativas descartadas com motivo real.
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
- Query nativa PostGIS em `infra/` com `@Query(nativeQuery=true)` e parâmetros nomeados, numa classe
  de sufixo **`*GeoRepository`** separada do `*Repository` JPA comum — é ela que a regra de "trocar
  PostGIS por Oracle Spatial em um arquivo" protege. Ver `CheckinGeoRepository` e
  `PontoCustodiaGeoRepository` (ambos ainda vazios, implementação em F6/F7).
- Escrita de domínio auditável tem DUAS metades, e faltar uma não quebra nada em tempo de compilação:
  o método de serviço leva `@Auditavel(acao=..., entidade=...)`, e o DTO de resposta que ele devolve
  implementa `RecursoAuditavel.idAuditoria()`. Sem a anotação o `AuditoriaAspecto` é advice que nunca
  dispara; sem a interface ele grava `entidade_id` nulo e a trilha vira "alguém publicou uma missão"
  sem dizer QUAL — inútil para reconstruir incidente. Padrão a copiar: os 9 métodos anotados em
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
- **Armadilha da primeira leitura, agora em duas entidades.** Se `Carteira` (ou `Missao`, ou
  `Usuario`) já estiver no persistence context, o Hibernate devolve a instância em cache SEM reemitir
  o `SELECT ... FOR UPDATE` — o teste passa e o lock nunca existiu. Por isso resolver
  `usuarioId → carteiraId` usa a projeção escalar `buscarIdPorUsuario`, nunca `findByUsuarioId`.
- Erro de regra de negócio no servidor é `RegraNegocioVioladaException` → **422**, não 409. O 409 diz
  "não cabe neste estado, caberia em outro"; o 422 diz "cabe no estado, mas os dados não satisfazem".
  A ordem de checagem é 403 → 409 → 422: inverter 409 e 422 quebra o contrato que o app já integra.
- Evento de domínio vai para a outbox por `PublicadorEventos`, na mesma transação do fato. Nunca
  notifique direto: antes do commit você anuncia o que o rollback desfaz; depois, perde o que falhar.
- Ao escrever teste, saiba o que `application-test.yml` desliga de propósito — três coisas, todas
  para não mascarar o que o teste mede:
  - rate limit de leitura/escrita em 10000/min: um teste de rate limit precisa sobrescrever o valor;
  - `app.agendamento.habilitado: false`: o job de expiração não roda, para não mudar status entre
    arrange e assert. A regra é testada chamando `expirarLote()` direto;
  - pool Hikari em 20, porque o teste de aceite concorrente dispara 50 threads.
- Para autenticar em teste sem passar pelo `/auth/login`, use `JwtTestConfig.gerarTokenValido(...)`
  e `gerarTokenExpirado(...)`. Login real esbarra no bloqueio de 5 tentativas/min, e um teste com
  muitos usuários falharia por 429 em vez de pela regra em avaliação. Fixture de missão:
  `MissaoFixture`.
- O `@Primary` do `JwtTestConfig` **não** impede o `JwtService` real de ser instanciado — `@Primary`
  só desempata injeção quando há mais de um candidato. O `@PostConstruct` do bean real roda de
  qualquer jeito e lê os PEM do disco, e é por isso que as chaves são obrigatórias mesmo para rodar
  só testes, e que `api.yml` tem um passo `gerar-chaves-dev.sh` antes do `verify`. Remover esse
  passo do CI derruba TODA a suíte de integração no GitHub enquanto tudo continua verde local.

**Mobile** (`apps/mobile/`): não iniciado (F9+), diretório vazio. As convenções vivem em
`apps/mobile/CLAUDE.md`, que entra em contexto ao mexer lá — não duplicadas aqui.

## Regras não negociáveis

Versões

- NUNCA escreva número de versão de memória. Verifique no Maven Central, npm ou start.spring.io.
  Se não conseguir verificar, pare e pergunte.
- No mobile use `npx expo install`, nunca `npm install` direto, para pacotes do ecossistema Expo.

Banco

- Flyway é a ÚNICA fonte de schema. ddl-auto é sempre validate. Nunca resolva divergência mudando
  ddl-auto — escreva migration.
- **Versão de migration é sequência GLOBAL, não por diretório.** Duas faixas, separadas de propósito:
  - `db/migration` — schema, **V1–V8, V11, V13 e V14**; único location do perfil default/prod.
    Próxima é **V15**. A **V12 está reservada** para `V12__checkin_idempotencia.sql`, que existe na
    branch `feat/f6-geolocalizacao` — F5 pulou para V13 de propósito, porque duas migrations com
    versão 12 derrubariam o merge das duas fases com *"more than one migration with version 12"*.
    **V9 e V10 estão queimadas — nunca as reutilize.** Foram os arquivos de seed antes da
    renomeação para `V900__seed_dev.sql`, então um banco de dev criado antes dela tem as versões 9
    e 10 gravadas no `flyway_schema_history` com descrição de seed. Um `V9__*.sql` novo em
    `db/migration` passaria em clone novo e falharia em máquina antiga com erro de checksum ou
    "detected applied migration not resolved locally" — divergência que não aparece no CI.
  - `db/seed` — só dev e test (via `application-dev.yml` / `application-test.yml`), faixa **900+**.
  - A faixa 900+ garante por construção que o seed roda depois de todo schema. Seed novo usa
    V901, V902… e NUNCA um número que o schema possa alcançar. Ver ADR 0006, Notas de manutenção.
  - Como o seed é o último, ele grava dados em forma final: não conte com migration posterior para
    corrigir valor de seed.
  - **Ao RENOMEAR uma migration, rode `./mvnw clean`** (além do `make reset`). O Maven não remove de
    `target/classes` o arquivo com o nome antigo, então o Flyway acha os dois e aplica os dois — o
    sintoma é `duplicate key value violates unique constraint`, que não parece ter relação nenhuma
    com renomear arquivo. CI não sofre disso: clona do zero.
- Dinheiro: numeric(12,2) → BigDecimal. Tokens: bigint. Nunca double, nunca String.
- Coordenada: geography(POINT,4326). Distância é derivada por PostGIS, nunca armazenada.
- Extensões `postgis` e `pgcrypto` são habilitadas via `docker/init/01-extensions.sql` e `V1__extensoes.sql`. `pgcrypto` provê `gen_random_uuid()` no banco.
- timestamptz, nunca timestamp. Enum: varchar + CHECK + EnumType.STRING, nunca ordinal.
- lancamento e auditoria são APPEND-ONLY. Correção por ESTORNO, nunca UPDATE.
- Toda consulta geoespacial fica isolada em uma classe de repositório `*GeoRepository` (permite
  trocar PostGIS por Oracle Spatial em um arquivo, se a parceria FIAP-Oracle vier a ser usada).

Segurança

- Nenhum segredo em arquivo versionado. Só ${VARIAVEL}, com .env.example commitado.
- Identidade do usuário vem SEMPRE do JWT. Nunca do corpo, query ou header.
- Controller nunca recebe nem devolve entidade JPA. Sempre DTO/record.
- SQL sempre com parâmetro bindado, inclusive nas queries PostGIS. Zero concatenação.
- Erro é RFC 9457 ProblemDetail. Nunca stack trace, SQL, nome de classe ou mensagem de driver.
- Nunca logue senha, token, refresh, coordenada exata ou payload de requisição autenticada.
- Mobile: credencial em expo-secure-store. NUNCA AsyncStorage.
- Validação geoespacial e de saldo é SEMPRE no servidor. Valor calculado no cliente é ignorado.
- HMAC de webhook é sobre o CORPO BRUTO, não o objeto desserializado, comparado em tempo constante.
- Deep link é entrada não confiável: valide esquema, host e formato antes de navegar.
- Transferência entre carteiras trava as duas em ordem determinística (ordene por id da carteira),
  sob pena de deadlock.

Testes

- Todo endpoint novo nasce com teste de caminho feliz e de erro. Fase sem teste verde não está pronta.
- Integração usa Testcontainers com PostGIS real. Nunca H2 para geoespacial.
- Operação de valor (aceite, crédito, transferência, saque) exige teste de concorrência multi-thread.
- Não escreva teste sem assertion para subir cobertura.

Git

- Conventional Commits. Uma branch por fase: feat/f6-geolocalizacao. Nunca commite na main direto.
- NUNCA git push --force nem git reset --hard sem eu pedir explicitamente.
- Antes de commitar, confira que não há segredo no diff.

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

## Estado atual

Fase atual: F5 (Concluído) — F6 (Geolocalização) é a próxima. Ver docs/PROGRESSO.md.

Módulo `missoes`: máquina de estados em `StatusMissao` + `MissaoStateMachine` (9 estados, **13**
transições — ver ADR 0006, revisto na F5), endpoints em `/api/v1/missoes`, aceite com lock
pessimista. Só `checkin` ainda responde **501**, até F6 — já valida 403 e 409 antes, então F6 troca
só o corpo do método.

Módulo `carteira`: ledger append-only, conclusão de missão creditando na mesma transação,
transferência P2P com ordem determinística de lock, saque, extrato, financiamento com pote e
reconciliação admin. Outbox transacional drenada por `@Scheduled`. Decisões no ADR 0008; a evidência
de concorrência (100 threads, deadlock, rollback) está em `docs/qualidade/integridade-transacional.md`
e é o documento a defender oralmente.

`CONCLUIDA` continua sendo o ÚNICO estado que credita — a regra que o protótipo descartado violava.

Contagem de testes e evidência de build NÃO ficam neste arquivo — envelhecem a cada PR. Fonte:
docs/PROGRESSO.md e docs/qualidade/.

Usuários seed (perfis `dev` e `test`, carregados via `db/seed/V900__seed_dev.sql`, senha `Senha@123`):
`admin@omnitribo.dev` (ADMIN) · `alice` e `carol` (USUARIO, Tribo Pinheiros e Vila Madalena) · `bob`, `diana`, `erik` (USUARIO). Ver docs/INFRA.md para lista completa com tribos.

## Pendências conhecidas

**BRL não tem lastro, e isso precisa de decisão ANTES da F6.** O TOKEN tem sumidouro (o pote da
missão); o BRL não tem nenhum. `missao.valor_brl` é escolhido livremente pelo criador e a conclusão
credita esse valor na carteira do executor **sem débito correspondente em lugar nenhum** — não há
escrow no publicar, e o único DEBITO em BRL do sistema é o saque. Dois usuários combinados criam,
aceitam e confirmam missões ENTREGA para gerar saldo sacável indefinidamente.

Hoje o caminho está fechado **por acidente, não por controle**: `AGUARDANDO_CONFIRMACAO` só é
alcançável via `CHECKIN`, que responde 501 até a F6. **Quando a F6 implementar o check-in, isso vira
impressora de dinheiro sem nenhuma mudança no módulo `carteira`.** As duas saídas: o publicar debita
ou escrowa a carteira do criador (mesmo padrão do pote de tokens), ou o BRL é declarado fictício
nesta etapa e `POST /carteira/saques` ganha um gate explícito. Detalhamento em
`docs/qualidade/integridade-transacional.md`, seção "O que esta fase NÃO garante".

Seção para armadilhas diagnosticadas e ainda não corrigidas. Ao resolver uma, remova-a daqui.
