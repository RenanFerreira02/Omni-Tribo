# Verificação — 2026-08-15 (resiliência das integrações e gates bloqueantes)

**Branch:** `develop`
**Protocolo:** `/verificar`, com a saída real de cada comando colada abaixo.
**Passo 0 dispensado:** nenhuma migration nova nesta fase, então `make reset` não é necessário.

---

## Resultado

| Área | Comando | Resultado |
|--------------|-------------------------------------------|-----------|
| Backend | `./mvnw verify` | ✅ BUILD SUCCESS — **637 testes**, 0 falhas / 0 erros / 2 pulados (58,8 s) |
| Cobertura (gate) | `jacoco:check` ×2 | ✅ *All coverage checks have been met* — 91,90% global, 92,01% domínio |
| Análise estática | `spotbugs:check` | ✅ *BugInstance size is 0* (effort `Max`, threshold `Medium`) |
| Formatação | `spotless:check` | ✅ 328 arquivos limpos |
| Arquitetura | `RegrasArquiteturaTest` | ✅ 3 regras, bloqueantes no `verify` |
| Mobile | `npm run typecheck && npm run lint && npm test` | ✅ 0 erros de tipo, 0 erros de lint (9 avisos preexistentes), **179 testes** em 14 suítes |
| Dependências | `./mvnw -Pseguranca verify` | ⚠️ **não executado** — exige `NVD_API_KEY` (ver abaixo) |
| Infra | `docker compose ps` | ⚪ não aplicável — esta máquina usa **podman**, e a suíte sobe o banco por Testcontainers |

---

## 1. Backend — `./mvnw verify`

```
[INFO] Results:
[INFO]
[INFO] Tests run: 637, Failures: 0, Errors: 0, Skipped: 2
[INFO]
[INFO] --- jacoco:0.8.15:check (check-global) @ api ---
[INFO] All coverage checks have been met.
[INFO] --- jacoco:0.8.15:check (check-dominio) @ api ---
[INFO] All coverage checks have been met.
[INFO] --- spotless:3.9.0:check (spotless-check) @ api ---
[INFO] Spotless.Java is keeping 328 files clean
[INFO] --- spotbugs:4.10.3.0:check (spotbugs-check) @ api ---
[INFO] BugInstance size is 0
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  58.845 s
```

**21 testes novos** nesta fase: `DisjuntorTest` (10), `ResilienciaClientesExternosTest` (7),
`ContratoOpenApiTest` (4).

Cobertura medida a partir de `target/site/jacoco/jacoco.csv`:

| Escopo | Instruções | Branches |
|---|---:|---:|
| Global | **91,90%** | 74,97% |
| `**/dominio/**` agregado | **92,01%** | — |
| `integracoes.infra` (código novo) | **94,55%** | — |

## 2. Prova de que os gates MORDEM

Um gate que nunca reprovou não é gate. Os dois foram exercitados contra uma falha real.

**JaCoCo** — mínimo do domínio subido temporariamente para 0,99:

```
[WARNING] Rule violated for bundle api: instructions covered ratio is 0.92, but expected minimum is 0.99
[ERROR] Failed to execute goal org.jacoco:jacoco-maven-plugin:0.8.15:check (check-dominio) on project api: Coverage checks have not been met.
```

O `0.92` é a razão REAL do domínio (bate com os 92,01% medidos no CSV), e não `0.00` — o que prova
que o `<includes>` casa as classes de domínio e a regra **não passa por vácuo**. Mínimo restaurado
para 0,85.

**Contrato do OpenAPI** — endpoint temporário marcado com `@Hidden`:

```
[ERROR] ContratoOpenApiTest.todo_endpoint_registrado_esta_descrito_no_openapi:68
[endpoints que existem e o OpenAPI não descreve — o app integra contra a documentação, então o que
falta aqui é contrato invisível]
Expecting empty but was: ["GET /api/v1/endpoint-fantasma-para-provar-o-gate"]
```

Endpoint temporário removido em seguida.

**Descoberta durante essa prova, e ela mudou o teste.** Acrescentar um endpoint comum **não** faz o
teste falhar: o springdoc deriva os caminhos do mesmo `RequestMappingHandlerMapping` que o teste
consulta, então endpoint novo nasce documentado sozinho. As comparações de caminho só pegam quem for
ESCONDIDO da documentação. Por isso foi acrescentada uma quarta asserção, que compara contra outra
fonte — a cadeia de segurança — e encontrou um defeito de verdade (§3).

## 3. Defeito encontrado e corrigido

`GET /api/v1/auth/me` e `POST /api/v1/auth/logout` exigem JWT (só `login`, `registrar`, `refresh`,
`ping` e o webhook são `permitAll`), mas o OpenAPI os descrevia como **anônimos** — o
`AuthController` era o único controller sem `@SecurityRequirement`. Quem integrasse pela documentação
escreveria um cliente que toma 401.

```
[ERROR] ContratoOpenApiTest.toda_operacao_protegida_declara_autenticacao_no_schema:111
Expecting empty but was: ["GET /api/v1/auth/me", "POST /api/v1/auth/logout"]
```

Corrigido anotando as duas operações; teste verde em seguida (4/4).

## 4. Mobile

```
Test Suites: 14 passed, 14 total
Tests:       179 passed, 179 total
```

`tsc --noEmit` sem saída (limpo). ESLint: **0 erros**, 9 avisos, todos preexistentes e não
relacionados a esta fase (`require()` em arquivos de configuração e `import/no-named-as-default-member`
do axios).

## 5. O que NÃO foi verificado (e por quê)

- **`./mvnw -Pseguranca verify` (OWASP Dependency-Check) não rodou até o fim.** O plugin 13.0.0
  exige chave da API da NVD para montar a base local:

  ```
  [ERROR] Failed to execute goal org.owasp:dependency-check-maven:13.0.0:check (dependency-check):
  [ERROR] 	UpdateException: Error updating the NVD Data
  [ERROR] 		caused by NvdApiException: Invalid API Key, length of 0 too short to provided a masked partial key
  [ERROR] 	NoDataException: No documents exist
  ```

  A chave é gratuita mas depende de cadastro, que não pode ser feito daqui. **O que ficou provado é a
  fiação**: o plugin executa, lê `dependency-check-suppressions.xml` e chega à etapa de aquisição de
  dados. Falta só `-Dnvd.api.key=$NVD_API_KEY`.

  Aprendizado registrado: **não configure a chave no pom com `${env.NVD_API_KEY}`** — variável
  ausente vira string vazia e produz exatamente o mesmo erro, o que faz parecer que a chave está
  errada quando na verdade não existe.

  > **Adendo de 2026-08-17.** Esta seção descreveu a falha como limitação da máquina local, e não
  > era. O secret também não existe no GitHub: o job `dependencias` do `Security Scan` foi
  > introduzido neste mesmo dia (`ca328fc`) e **reprovou nas 4 execuções seguintes**, sempre em
  > 25–36 s, pela mesma causa. O workflow ficou vermelho de 08-15 a 08-17 e nenhum documento
  > registrava isso. Ver [`../evidencias/f13-ci-github-actions.md`](../evidencias/f13-ci-github-actions.md).

- **`docker compose ps` não se aplica nesta máquina.** Não há Docker Desktop; o ambiente usa podman,
  e a suíte sobe o PostgreSQL+PostGIS por Testcontainers (`DOCKER_HOST` apontando para o socket do
  podman). O passo do `/verificar` que confere containers de longa duração não tem alvo aqui.

- **`npm run test:e2e` não rodou**, como sempre: exige o backend em execução e um endereço de rede
  que varia por máquina.

- **Nada foi exercitado contra ViaCEP ou Open-Meteo reais.** Os dois aparecem na suíte apenas como
  servidores locais; o comportamento deles sob falha verdadeira é suposição informada.

- **O status obrigatório de merge não foi ativado.** É configuração do GitHub e exige admin. O
  pré-requisito técnico foi feito (filtros `paths:` movidos para condicional de job), mas até que
  seja ligado, todos os gates acima continuam sendo conselho e não barreira.
