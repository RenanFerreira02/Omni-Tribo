# Verificação — 2026-08-06 (F3 + F4)

Branch `feat/f4-ciclo-vida-missoes`. Saídas reais dos comandos, sem edição.

---

## 1. Build completo — `./mvnw verify`

```
[INFO]  T E S T S
[INFO] Tests run:   1, Failures: 0, Errors: 0, Skipped: 0 -- com.omnitribo.arquitetura.RegrasArquiteturaTest
[INFO] Tests run:   2, Failures: 0, Errors: 0, Skipped: 0 -- com.omnitribo.compartilhado.api.PingControllerTest
[INFO] Tests run:  11, Failures: 0, Errors: 0, Skipped: 0 -- com.omnitribo.identidade.api.AuthControllerTest
[INFO] Tests run:   1, Failures: 0, Errors: 0, Skipped: 0 -- com.omnitribo.identidade.api.RefreshTokenFamiliaTest
[INFO] Tests run:   4, Failures: 0, Errors: 0, Skipped: 0 -- com.omnitribo.MigracaoTest
[INFO] Tests run:   4, Failures: 0, Errors: 0, Skipped: 0 -- com.omnitribo.missoes.dominio.ExpiracaoMissoesServiceTest
[INFO] Tests run: 118, Failures: 0, Errors: 0, Skipped: 0 -- com.omnitribo.missoes.dominio.MissaoStateMachineTest
[INFO] Tests run:   1, Failures: 0, Errors: 0, Skipped: 0 -- com.omnitribo.missoes.api.MissaoAceiteConcorrenteTest
[INFO] Tests run:  27, Failures: 0, Errors: 0, Skipped: 0 -- com.omnitribo.missoes.api.MissaoControllerTest
[INFO] Tests run:   7, Failures: 0, Errors: 0, Skipped: 0 -- com.omnitribo.MigracaoCicloVidaTest
[INFO] Tests run: 176, Failures: 0, Errors: 0, Skipped: 0
[INFO] Spotless.Java is keeping 108 files clean - 0 needs changes to be clean
[INFO] BugInstance size is 0
[INFO] BUILD SUCCESS
[INFO] Total time:  21.429 s
```

De 19 testes (pós-F2) para **176**. ArchUnit verde: nenhuma classe fora de `com.omnitribo.missoes..`
acessa `missoes.dominio` ou `missoes.infra`.

---

## 2. Matriz de transições — `./mvnw -Dtest=MissaoStateMachineTest test`

```
[INFO]  T E S T S
[INFO] Running com.omnitribo.missoes.dominio.MissaoStateMachineTest
[INFO] Tests run: 118, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.333 s
[INFO] BUILD SUCCESS
[INFO] Total time:  1.835 s
```

Os 118 casos são:

- **99** da matriz completa (9 status × 11 eventos). As 12 transições válidas mudam o status e
  produzem `MissaoEvento` com `deStatus`/`paraStatus`/`tipo` corretos; as outras 87 lançam
  `TransicaoInvalidaException` (409) **sem deixar mutação parcial** na missão.
- **11** de autorização, um por evento: ator errado recebe `AcessoNegadoException` (403) mesmo sobre
  uma missão em estado terminal — prova que a checagem de papel roda antes da de transição.
- **8** nomeados, entre eles `abertaNaoPodeSaltarParaConcluida`, `criadorNaoPodeAceitarAPropriaMissao`
  e `validarNaoMutaAMissao`.

A tabela esperada do teste é escrita à mão, independente do enum: derivá-la de `StatusMissao`
tornaria o teste tautológico — passaria mesmo se alguém apagasse uma transição.

Roda sem Spring, sem banco e sem HTTP: 0,3 s para 118 casos.

---

## 3. Aceite concorrente — `./mvnw -Dtest=MissaoAceiteConcorrenteTest test`

```
[INFO]  T E S T S
[INFO] Running com.omnitribo.missoes.api.MissaoAceiteConcorrenteTest
2026-08-06 10:02:33 [main] [] INFO  c.o.m.a.MissaoAceiteConcorrenteTest -
    Aceite concorrente com 50 threads → 200: 1 | 409: 49 | 429: 0 | 500: 0
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 9.935 s
[INFO] BUILD SUCCESS
[INFO] Total time:  12.513 s
```

50 usuários distintos, 50 tokens distintos, `CountDownLatch` de largada, HTTP real via MockMvc.
Além da distribuição, o teste confere no banco:

- `missao.status = 'ACEITA'` e `executor_id` é um dos 50 concorrentes;
- **exatamente um** evento `ACEITA` em `missao_evento`;
- **zero** linhas em `lancamento` para a missão — aceitar não credita nada, que é o furo do
  protótipo que esta fase fecha.

Os zeros importam: `429 = 0` prova que o rate limit não mascarou o resultado, e `500 = 0` prova que
nenhuma exceção de infraestrutura vazou no lugar do 409 de negócio.

---

## 4. Fluxo manual contra a aplicação real (perfil `dev`)

`docker compose up -d` + `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`:

```
Successfully applied 11 migrations to schema "public", now at version v11 (00:00.455s)
Tomcat started on port 8080 (http) / port 8081 (http)
Started ApiApplication in 4.173 seconds
```

Ciclo exercitado com `curl`, autenticando com os usuários do seed:

```
1. criar (alice)              → 201  status: RASCUNHO   criador: bbbbbbbb-…-0002
2. publicar (alice)           → 200  status: ABERTA
3. alice aceita a própria     → 403
4. bob aceita                 → 200  status: ACEITA  executor: bbbbbbbb-…-0003  concluidaEm: null
5. bob inicia                 → 200  status: EM_ANDAMENTO
6. checkin                    → 501  "Funcionalidade ainda não disponível nesta versão da API."
7. TRIBO com valorBrl: 10.00  → 400  [{campo: valorBrl, mensagem: "Missões TRIBO e COLETA
                                       não podem ter valor em BRL — recompensam em tokens e XP"}]
8. trilha em missao_evento:
      PUBLICADA  RASCUNHO -> ABERTA
      ACEITA     ABERTA   -> ACEITA
      INICIADA   ACEITA   -> EM_ANDAMENTO
```

`concluidaEm: null` no passo 4 é a evidência direta de que aceitar não conclui nem credita.

OpenAPI em `/v3/api-docs` responde 200, com `securitySchemes: [bearerAuth]`, as 7 respostas de erro
reutilizáveis registradas e **11 caminhos** sob `/api/v1/missoes` (13 operações).

---

## 5. Não verificado

- **Mobile** (`npm run typecheck && lint && test`): não executado. Nada em `apps/mobile/` foi tocado
  nesta fase.
- **Privilégios do role `omnitribo_app`**: dev e test conectam como dono do schema, então uma falta de
  `GRANT` não apareceria na suíte. Lacuna conhecida, registrada no ADR 0006 como candidata a F12.
- **Agentes `revisor-seguranca` e `revisor-testes`**: não executados nesta sessão. Recomendado rodar
  antes de abrir o PR, já que a fase mexe em autorização e no caminho que levará a crédito.

---

## 6. Achados durante a verificação

Três defeitos reais encontrados e corrigidos por rodar os testes, não por leitura de código:

1. **Toda listagem devolvia 400.** `int pagina` primitivo no `@ModelAttribute`: parâmetro de query
   ausente chega como `null` e o binder não converte para primitivo. Corrigido para `Integer` com
   default no construtor compacto. O teste `tamanhoDePaginaAcimaDoLimiteDa400` estava passando pelo
   motivo errado — passou a afirmar o **campo** do erro, não só o status.
2. **A consulta com filtros estourava em runtime.** `lower(:cidade)` com valor nulo vira
   `lower(bytea)` no PostgreSQL, porque o driver não infere o tipo de um parâmetro nulo. Corrigido
   com `cast(:cidade as string)`.
3. **A aplicação não subiria.** O `MissaoService` injetava
   `com.fasterxml.jackson.databind.ObjectMapper`, mas o Spring Boot 4.1 autoconfigura o mapper do
   **Jackson 3** (`tools.jackson`) — aquele bean não existe. Só apareceu porque
   `ExpiracaoMissoesServiceTest` usa `TesteIntegracaoBase` (contexto real, sem o fallback de
   `MockMvcTestConfig`).

---

## 7. Pendência de ambiente (fora do escopo desta fase)

`services/api/keys/` é gitignored e o `JwtService` real falha no boot sem os PEM, o que derruba todo
teste que estenda `TesteIntegracaoBase`. Localmente resolve-se com `bash tools/gerar-chaves-dev.sh` —
já anotado no CLAUDE.md.

**O `api.yml` do GitHub Actions não gera essas chaves**, então o CI reprova antes de chegar aos
testes. Não foi corrigido aqui por estar fora do escopo pedido; a correção é um passo
`run: bash tools/gerar-chaves-dev.sh` antes do `./mvnw verify`.
