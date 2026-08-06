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

```bash
# Backend
cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev  # sobe o servidor local (porta 8080; actuator na 8081)
cd services/api && ./mvnw verify                          # compila + todos os testes + spotless + spotbugs
cd services/api && ./mvnw spotless:apply                  # corrige formatação Google Java Format (rodar antes do verify se falhar em formatação)
cd services/api && ./mvnw -Dtest=NomeDaClasseTest test    # um único teste

# Mobile
cd apps/mobile && npm run android       # inicia no emulador
cd apps/mobile && npm run typecheck && npm run lint && npm test
cd apps/mobile && npx jest --testPathPattern=nomeDoArquivo  # um único teste

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
- `/adr <assunto>` — cria `docs/adr/NNNN-<slug>.md` com o próximo número. Template exige Alternativas descartadas com motivo real.
- Agente `revisor-seguranca` — revisa autenticação, autorização, endpoints de valor, webhooks, dados pessoais. Checar após implementar qualquer um desses.
- Agente `revisor-testes` — avalia se a suíte realmente garante comportamento (não conta testes, avalia o que cobrem). Rodar ao fechar fase.

## Convenções por camada

**Backend** (`services/api/`):
- DTOs são `record`. Entidade JPA nunca cruza fronteira do controller.
- Exceções de domínio herdam de `DominioException` → mapeadas para status HTTP no handler global → resposta RFC 9457 `ProblemDetail`.
- Identidade do usuário logado no controller: injete `@AuthenticationPrincipal AutenticadoPrincipal principal` — nunca extraia do corpo ou query string.
- Teste de integração: use `TesteIntegracaoBase` (RANDOM_PORT + TestRestTemplate) para roundtrip HTTP real; use `TesteIntegracaoMvcBase` (WebEnvironment.MOCK + MockMvc) quando precisar inspecionar headers de resposta. Ambas estendem `ContainerConfig` (PostgreSQL+PostGIS singleton). Spring Boot 4.1 removeu `@AutoConfigureMockMvc` — não tente usá-lo.
- Query nativa PostGIS em `infra/` com `@Query(nativeQuery=true)` e parâmetros nomeados.
- Spotless (Google Java Format) é verificado no `verify`. Se falhar por formatação, rode `./mvnw spotless:apply`.
- Jackson: Spring Boot 4.1 autoconfigura o mapper do **Jackson 3** (`tools.jackson`). Não existe bean de `com.fasterxml.jackson.databind.ObjectMapper` — injetá-lo impede o contexto de subir.
- Mudança de status de missão passa SEMPRE por `MissaoStateMachine`. Nunca chame `missao.setStatus(...)` fora dela.

**Mobile** (`apps/mobile/`):
- `app/` — só rotas do Expo Router; tela é composição, sem lógica de negócio.
- `src/features/<dominio>/` — hooks TanStack Query e lógica; `src/api/` é o único lugar que faz HTTP.
- `src/components/` — design system, sem chamada de API; `src/stores/` — Zustand só para UI e sessão.
- `src/theme/tokens.ts` — nenhum hex literal fora daqui.
- `any` só com comentário justificando.
- Toda chamada de API tem estado de carregando, vazio e erro tratados na UI.

## Regras não negociáveis

Versões

- NUNCA escreva número de versão de memória. Verifique no Maven Central, npm ou start.spring.io.
  Se não conseguir verificar, pare e pergunte.
- No mobile use `npx expo install`, nunca `npm install` direto, para pacotes do ecossistema Expo.

Banco

- Flyway é a ÚNICA fonte de schema. ddl-auto é sempre validate. Nunca resolva divergência mudando
  ddl-auto — escreva migration.
- Dinheiro: numeric(12,2) → BigDecimal. Tokens: bigint. Nunca double, nunca String.
- Coordenada: geography(POINT,4326). Distância é derivada por PostGIS, nunca armazenada.
- Extensões `postgis` e `pgcrypto` são habilitadas via `docker/init/01-extensions.sql` e `V1__extensoes.sql`. `pgcrypto` provê `gen_random_uuid()` no banco.
- timestamptz, nunca timestamp. Enum: varchar + CHECK + EnumType.STRING, nunca ordinal.
- lancamento e auditoria são APPEND-ONLY. Correção por ESTORNO, nunca UPDATE.
- Toda consulta geoespacial fica isolada em uma classe de repositório (permite trocar PostGIS por
  Oracle Spatial em um arquivo, se a parceria FIAP-Oracle vier a ser usada).

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

Fase atual: F4 (Concluído, junto com F3) — F5 (Carteira e Economia) é a próxima fase. Ver docs/PROGRESSO.md.

Módulo `missoes` implementado: máquina de estados em `StatusMissao` + `MissaoStateMachine`
(9 estados, 12 transições, ver ADR 0006), endpoints em `/api/v1/missoes`, aceite com lock pessimista.
Três endpoints publicam contrato e respondem **501** até suas fases: `checkin` (F6), `confirmar` e
`resolver` (F7) — todos já validam 403 e 409 antes do 501, então F6/F7 só trocam o corpo do método.
Crédito em carteira NÃO existe em nenhum caminho ainda: só a entrada em `CONCLUIDA` poderá creditar.

Antes de rodar a suíte pela primeira vez num clone novo: `bash tools/gerar-chaves-dev.sh`
(`services/api/keys/` é gitignored e os testes de `TesteIntegracaoBase` carregam o `JwtService` real).

Usuários seed (perfis `dev` e `test`, carregados via `db/seed/V9__seed_dev.sql`, senha `Senha@123`):
`admin@omnitribo.dev` (ADMIN) · `alice` e `carol` (USUARIO, Tribo Pinheiros e Vila Madalena) · `bob`, `diana`, `erik` (USUARIO). Ver docs/INFRA.md para lista completa com tribos.
