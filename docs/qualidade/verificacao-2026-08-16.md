# Verificação — 2026-08-16 (F13, entrega final)

Executada na branch `docs/f13-entrega-final`, com o roteiro do `/verificar`.
Máquina: Fedora Linux · JDK 21 (SDKMAN) · Node 22 · **podman** (`DOCKER_HOST` exportado para o
socket do usuário, sem o qual o Testcontainers não sobe).

## Passo 0 — `make reset` era necessário?

**Não.** O diff da branch contra `develop` não toca `db/migration` nem `db/seed`:

```console
$ git diff --name-only develop...HEAD -- '*db/migration*' '*db/seed*'
(vazio)
```

O passo 0 existe porque o seed vive na faixa V900+: migration nova nasce *out-of-order* e o boot
falha com `Detected resolved migration not applied to database`. Sem mudança de schema, não se
aplica.

> O banco **foi** recriado do zero nesta fase, mas por outro motivo: testar o README a partir do
> nada. Ver [`../evidencias/f13-execucao-do-zero.md`](../evidencias/f13-execucao-do-zero.md).

## Passo 1 — Backend

```console
$ cd services/api && ./mvnw -q verify
EXIT=0
```

Execução completa, com a saída detalhada de contagem, em
[`../evidencias/f13-make-test.md`](../evidencias/f13-make-test.md):

| Área | Resultado |
|---|---|
| Testes | ✅ **637**, 0 falhas, 0 erros, 2 pulados |
| Cobertura | ✅ `jacoco:check` ×2 — 80% global e 85% em `dominio` |
| Análise estática | ✅ `spotbugs:check` com `failOnError` |
| Formatação | ✅ `spotless:check` — 328 arquivos limpos |

## Passo 2 — Mobile

```console
$ npm run typecheck
> tsc --noEmit
(sem saída = sem erro)

$ npm run lint
✖ 9 problems (0 errors, 9 warnings)

$ npm test
Test Suites: 14 passed, 14 total
Tests:       179 passed, 179 total
Snapshots:   0 total
Time:        4.645 s, estimated 5 s
```

Os 9 avisos são pré-existentes e não introduzidos nesta fase — o mais frequente é
`import/no-named-as-default-member` em `src/api/erros.ts:163`, sobre `axios.isAxiosError`. **Zero
erros.**

## Passo 3 — Git e segredos

```console
$ git status --short
(limpo)

$ git diff develop...HEAD | grep -E "BEGIN .*PRIVATE KEY|AIza[0-9A-Za-z_-]{35}|(senha|password|secret|token|api_key)\s*=\s*\"[^\"]{8,}\""
nenhum segredo encontrado no diff da branch
```

## Passo 4 — Containers

```console
$ docker compose ps
NAME           IMAGE                              SERVICE   STATUS
omnitribo-db   docker.io/postgis/postgis:16-3.5   db        Up 58 minutes
```

## Verde, vermelho e não verificado

**Verde:** backend (`verify` com testes, cobertura, SpotBugs e Spotless), mobile (typecheck, lint,
testes), ausência de segredo no diff, container do banco de pé. Além do roteiro padrão, esta fase
executou o ciclo completo por HTTP, os 6 cenários do webhook e a medição de conservação por
categoria — tudo em [`../evidencias/`](../evidencias/).

**Vermelho:** nada.

**Não verificado, e por quê:**

- **`npm run test:e2e`** — fica fora do `/verificar` de propósito: exige o backend em execução e um
  endereço de rede que varia por máquina. Foi exercitado à parte nesta fase, com o servidor de pé.
- **Varredura de dependências (OWASP)** — vive no profile `seguranca` e **exige** chave da NVD.
  Sem `NVD_API_KEY` o plugin aborta com *"Invalid API Key, length of 0"*.
- **Testes de carga e desempenho** — é a F12b, pendente. Nenhum número de latência, TPS ou
  disponibilidade é afirmado neste repositório.
- **Build EAS** — o `eas.json` foi configurado e **não** executado; exige conta Expo e rede.
- **Outros sistemas operacionais** — tudo aqui foi medido em Linux com podman.
