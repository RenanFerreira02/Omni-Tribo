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
Cada um com api/ (controllers, DTOs), dominio/ (entidades, regras), infra/ (repositórios, clientes).
Regra verificada por ArchUnit: módulo só acessa outro por api/ pública ou evento. Nunca repositório
ou entidade JPA alheia. carteira referencia missao_id como UUID puro, sem FK, deliberadamente.

Maturidade real por módulo (o alvo é o de cima; o de hoje é este):
- Três camadas completas: `compartilhado`, `identidade`, `missoes`.
- `api/` + `dominio/` + `infra/`, mas sem controller próprio — expõe porta consumida por outro
  módulo: `geolocalizacao` (`RegistroCheckin`, implementada em F6).
- Só `dominio/` + `infra/` — entidades e repositórios, nenhum controller: `carteira`, `logistica`.
- Vazio, só `.gitkeep`: `notificacoes`.

Módulo só fala com outro pela `api/` pública. O check-in é o exemplo canônico: `MissaoService`
(em `missoes.dominio`) injeta a **interface** `geolocalizacao.api.RegistroCheckin` — nunca a
implementação —, e só records de tipos da JDK atravessam a fronteira nos dois sentidos.

O schema de TODOS já existe desde V4–V7: o banco está à frente do código. Encontrar tabela sem
código correspondente é o estado esperado, não resíduo.

`RegrasArquiteturaTest` aplica a regra aos 6 módulos de negócio; `compartilhado` é **isento** por
ser shared por design (ver o array `MODULOS` no teste). Violação em `compartilhado` não é pega por
teste nenhum — é só disciplina.

## Economia (três moedas)

XP: reputação, não transferível, monotônico, sem ledger.
BRL: dinheiro real, ACID rigoroso. Missões ENTREGA e AJUDA.
TOKEN: moeda comunitária, transferível na mesma tribo. Missões TRIBO e COLETA.
Regra: missão TRIBO ou COLETA não pode ter valor_brl > 0.

## Stack

Backend: Spring Boot 4.1 · Java 21 · Maven · PostgreSQL+PostGIS · Flyway
Mobile: Expo SDK 57 · TypeScript strict · Expo Router · TanStack Query · Zustand
Testes: JUnit 5 · Testcontainers · ArchUnit · Jest/RTL/MSW

## Comandos

> `make seed` e `make test` ainda são stubs. Os demais targets (`up`, `down`, `reset`, `logs`, `ps`, `psql`) estão implementados.

Clone novo exige UM passo antes de `./mvnw verify` ou de subir o servidor:

```bash
bash tools/gerar-chaves-dev.sh  # services/api/keys/ é gitignored; sem PEM nenhum contexto Spring sobe
```

O `.env` NÃO precisa mais ser copiado à mão: todo target do Makefile que lê o compose tem `.env`
como pré-requisito de arquivo e o cria a partir do `.env.example`. Só ajuste credenciais se o
default (`omnitribo`/`omnitribo_dev`) não servir.

```bash
# Backend
cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev  # sobe o servidor local (porta 8080; actuator na 8081)
cd services/api && ./mvnw verify                          # compila + todos os testes + spotless + spotbugs
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

## Automação

- Hook `PreToolUse` (`.claude/hooks/checar-segredo.sh`) bloqueia `git commit` se o diff staged tiver
  padrão de chave/senha/token — não é substituto de revisão manual, é uma segunda barreira.
- CI (`.github/workflows/`): `api.yml` roda `./mvnw verify` a cada push/PR que toque `services/api/**`;
  `security.yml` roda Gitleaks no histórico completo em todo push/PR.

## Skills e agentes disponíveis

- `/verificar` — roda verificação completa (mvnw verify + typecheck + lint + test + docker compose ps) e reporta verde/vermelho. Use antes de abrir PR. **Nunca declare sucesso sem rodar isso.**
  Hoje o passo 2 (mobile) falha no shell, porque `apps/mobile/` não tem `package.json` — os passos
  1, 3 e 4 continuam válidos. Correção da skill está em Pendências conhecidas.
- `/adr <assunto>` — cria `docs/adr/NNNN-<slug>.md` com o próximo número. Template exige Alternativas descartadas com motivo real.
- Agente `revisor-seguranca` — revisa autenticação, autorização, endpoints de valor, webhooks, dados pessoais. Checar após implementar qualquer um desses.
- Agente `revisor-testes` — avalia se a suíte realmente garante comportamento (não conta testes, avalia o que cobrem). Rodar ao fechar fase.

## Convenções por camada

**Backend** (`services/api/`):
- DTOs são `record`. Entidade JPA nunca cruza fronteira do controller.
- Exceções de domínio herdam de `DominioException` → mapeadas para status HTTP no handler global → resposta RFC 9457 `ProblemDetail`.
- Identidade do usuário logado no controller: injete `@AuthenticationPrincipal AutenticadoPrincipal principal` — nunca extraia do corpo ou query string.
- Teste de integração: use `TesteIntegracaoBase` (RANDOM_PORT + TestRestTemplate) para roundtrip HTTP real; use `TesteIntegracaoMvcBase` (WebEnvironment.MOCK + MockMvc) quando precisar inspecionar headers de resposta. Ambas estendem `ContainerConfig` (PostgreSQL+PostGIS singleton). Spring Boot 4.1 removeu `@AutoConfigureMockMvc` — não tente usá-lo.
- **Toda função PostGIS vive em `compartilhado/infra/ConsultasGeoespaciais` — uma classe só, no
  repositório inteiro** (ADR 0007, que substitui a regra "um repo geo por módulo" do ADR 0002). Usa
  `JdbcClient`, não `@Query(nativeQuery=true)`: este exige interface ligada a uma `@Entity`, e a
  única visível de `compartilhado` seria `Outbox`. Parâmetros nomeados seguem obrigatórios, zero
  concatenação. A classe **não pode importar tipo de módulo** (a regra ArchUnit é direcional):
  filtros entram como String, o retorno é um par neutro id+distância. Query nativa que NÃO seja
  geoespacial continua em `infra/` do próprio módulo, com `@Query(nativeQuery=true)`.
- Spotless (Google Java Format) é verificado no `verify`. Se falhar por formatação, rode `./mvnw spotless:apply`.
- Jackson é **3** (`tools.jackson`) em todo o repositório, main e test. Spring Boot 4.1 autoconfigura
  esse mapper e não existe bean de `com.fasterxml.jackson.databind.ObjectMapper` — injetá-lo impede
  o contexto de subir. Quem precisa serializar constrói o próprio `JsonMapper`, sem injeção: veja
  `MissaoService.MAPPER_TRILHA` no main e `TesteIntegracaoMvcBase.JSON` nos testes. Em teste novo,
  use `JSON` — não declare bean de mapper.
- AOP: `spring-boot-starter-aop` não existe no Boot 4.x. O suporte a `@Aspect` vem de
  `aspectjweaver` declarado direto no `pom.xml`.
- Mudança de status de missão passa SEMPRE por `MissaoStateMachine`. Nunca chame `missao.setStatus(...)` fora dela.
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
  - `db/migration` — schema; único location do perfil default/prod. Existem V1–V8, V11 e V12.
    Próxima é **V13**. **V9 e V10 são buracos permanentes, não arquivos faltando**: eram o seed antigo, hoje
    consolidado em `V900__seed_dev.sql`. Nunca reaproveite esses números: `out-of-order` é `false`
    (explícito em `application-dev.yml`, default nos demais), então um V9 novo faz o Flyway FALHAR
    na validação em qualquer banco que já passou da V11 — enquanto o CI, que clona do zero, aplica
    e passa. Divergência que só aparece na sua máquina.
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
- Toda consulta geoespacial fica isolada em UMA classe, `compartilhado/infra/ConsultasGeoespaciais`
  (permite trocar PostGIS por Oracle Spatial em um arquivo, se a parceria FIAP-Oracle vier a ser
  usada). Ver ADR 0007 — a regra antiga, "uma por módulo", produzia dois arquivos com `ST_*`.

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

Fase atual: F6 (Concluído). **F5 (Carteira e Economia) segue PENDENTE** — a F6 foi feita fora de
ordem. F5 e F7 são as próximas. Ver docs/PROGRESSO.md.

Módulo `missoes` implementado: máquina de estados em `StatusMissao` + `MissaoStateMachine`
(9 estados, 12 transições, ver ADR 0006), endpoints em `/api/v1/missoes`, aceite com lock pessimista.
Dois endpoints ainda publicam contrato e respondem **501**: `confirmar` e `resolver` (F7) — validam
403 e 409 antes do 501, então a F7 só troca o corpo do método.
Crédito em carteira NÃO existe em nenhum caminho ainda: só a entrada em `CONCLUIDA` poderá creditar.

F6 entregou o radar (`GET /missoes/proximas`) e o check-in geolocalizado. Três coisas que valem
saber antes de mexer:
- Todo `ST_*` do repositório está em `compartilhado/infra/ConsultasGeoespaciais` (ADR 0007).
- O check-in grava a linha em transação `REQUIRES_NEW`, e é isso que faz a trilha de auditoria
  sobreviver ao 422 da rejeição. Não "simplifique" para uma transação só — a rejeição some.
- O cache de proximidade invalida em `afterCommit`, não durante a transação, e tem **cinco** pontos
  de invalidação. `ExpiracaoMissoesService` é um deles e não passa por `MissaoService.aplicar`.

Antes de rodar a suíte pela primeira vez num clone novo: `bash tools/gerar-chaves-dev.sh`
(`services/api/keys/` é gitignored e os testes de `TesteIntegracaoBase` carregam o `JwtService` real).

Contagem de testes e evidência de build NÃO ficam neste arquivo — envelhecem a cada PR. Fonte:
docs/PROGRESSO.md e docs/qualidade/.

Usuários seed (perfis `dev` e `test`, carregados via `db/seed/V900__seed_dev.sql`, senha `Senha@123`):
`admin@omnitribo.dev` (ADMIN) · `alice` e `carol` (USUARIO, Tribo Pinheiros e Vila Madalena) · `bob`, `diana`, `erik` (USUARIO). Ver docs/INFRA.md para lista completa com tribos.

## Pendências conhecidas

Nenhuma no momento. As quatro registradas em 2026-08-06 (numeração dos seeds, guarda de `.env`,
skill `/verificar`, bean `ObjectMapper`) foram resolvidas — ver docs/PROGRESSO.md.

Seção para armadilhas diagnosticadas e ainda não corrigidas. Ao resolver uma, remova-a daqui.
