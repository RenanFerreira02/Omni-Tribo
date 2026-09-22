# Verificação completa da entrega final — saída real

**Data:** 2026-09-12 · **Fase:** nenhuma (ver nota abaixo) · **Branch:** `develop` · **HEAD:** `c5a8426`
**Comando:** a skill `/verificar`, com duas substituições declaradas na §1.

Esta evidência acompanha [`../auditoria/entrega-final.md`](../auditoria/entrega-final.md) e existe para
sustentar os números que o `README.md` publica na seção **Estado**. Antes dela, aquela seção citava
[`f13-make-test.md`](./f13-make-test.md) — arquivo de **2026-08-16**, que registra 637 testes no
backend e 179 no mobile — para afirmar 706 e 221, medidos numa execução de 2026-08-25 cuja saída não
existe em lugar nenhum do repositório. Os números abaixo são os de uma execução única, inteira, hoje.

**Nota sobre o nome do arquivo.** Ele não tem o prefixo `f<fase>-` que a
[convenção do diretório](./README.md) pede, pelo mesmo argumento que o índice já registra para
`impacto-conferido-por-sql.md`: `docs/PROGRESSO.md` é a numeração de verdade, e a tabela de fases dele
**não tem F14 a F17** — todas mergeadas. Inventar um `f18-` criaria contradição com ele. A ausência
de linha de fase para F14–F17 é, ela própria, um achado do relatório de auditoria (item 6).

---

## 1. As duas substituições, e por que cada uma

A skill prescreve quatro passos. Dois precisaram de ajuste, e registrá-los é parte da evidência —
saída obtida por um comando diferente do prescrito, sem dizer qual, é o começo de um número que
ninguém consegue reproduzir.

| Passo da skill | O que foi executado | Por quê |
|---|---|---|
| 1 — `cd services/api && ./mvnw -q verify` | `./mvnw verify`, **sem `-q`**, com `DOCKER_HOST` exportado | O `-q` suprime as linhas de Surefire, Spotless, SpotBugs e JaCoCo — exatamente as que esta evidência precisa citar. E o Testcontainers **não** passa pelo wrapper `tools/demo/compose.sh`: sem `export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock` o passo quebra ao subir o contêiner, porque nesta máquina o CLI do Docker sobreviveu ao Docker Desktop desinstalado e aponta para um socket morto. O `README.md:442-446` já documenta esse export. |
| 4 — `docker compose ps` | `make ps` | Mesmo motivo: `docker` direto falha com `Cannot connect to the Docker daemon at unix:///home/renan/.docker/desktop/docker.sock`. `make ps` passa por `tools/demo/compose.sh`, que resolve o socket rootless do podman sozinho. **O passo 4 da skill está desatualizado para esta máquina** e deveria dizer `make ps`. |

O passo 0 (`make reset` antes do passo 1) **não se aplicou**: nada em `db/migration` nem em `db/seed`
foi tocado nesta entrega. O banco foi recriado de todo modo, às 22:42, porque a auditoria que
acompanha esta evidência precisava de banco pristino para medir a invariante econômica.

---

## 2. Passo 1 — backend: `./mvnw verify`

```console
$ export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
$ cd services/api && ./mvnw verify
### inicio: 2026-09-12 23:50:15

[INFO] Results:
[INFO] Tests run: 755, Failures: 0, Errors: 0, Skipped: 2

[INFO] --- jacoco:0.8.15:report (report) @ api ---
[INFO] Analyzed bundle 'omnitribo-api' with 313 classes
[INFO] --- jacoco:0.8.15:check (check-global) @ api ---
[INFO] Analyzed bundle 'api' with 313 classes
[INFO] All coverage checks have been met.
[INFO] --- jacoco:0.8.15:check (check-dominio) @ api ---
[INFO] Analyzed bundle 'api' with 139 classes
[INFO] All coverage checks have been met.
[INFO] --- spotless:3.9.0:check (spotless-check) @ api ---
[INFO] Spotless.Java is keeping 419 files clean - 0 needs changes to be clean, 60 were already clean, 359 were skipped because caching determined they were already clean
[INFO] --- spotbugs:4.10.3.0:check (spotbugs-check) @ api ---
[INFO] BugInstance size is 0
[INFO] Error size is 0
[INFO] No errors/warnings found
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  01:32 min
[INFO] Finished at: 2026-09-12T23:51:49-03:00
### EXIT=0
```

**Os dois pulados são deliberados e não são falha mascarada.** São
`com.omnitribo.logistica.treino.ExportadorDatasetCsvTest` e
`com.omnitribo.logistica.treino.RelatorioTreinoTest` — os dois que escrevem artefato do modelo de
risco em `tools/dataset/`, pulados fora do passe de regeneração. Nenhum `@Disabled` foi acrescentado.

### Cobertura, lida do relatório desta execução

`services/api/target/site/jacoco/jacoco.xml`, gerado às 23:51 por este `verify`:

| Contador | Coberto / total | % | Gate |
|---|---|---|---|
| INSTRUCTION (global) | 18 624 / 20 095 | **92,68 %** | ≥ 80 % — passa |
| INSTRUCTION (pacotes `dominio`) | 11 437 / 12 351 | **92,60 %** | ≥ 85 % — passa |
| LINE (global) | 4 266 / 4 601 | 92,72 % | sem gate |
| BRANCH (global) | 879 / 1 151 | **76,37 %** | sem gate, **de propósito** |

O gate de `dominio` mede **139 classes**, não zero: o `<includes>` casa, então o check não passa por
vácuo — que é a armadilha que o `CLAUDE.md` manda conferir. BRANCH segue sem gate e segue em ~76 %;
ligá-la fecharia o build vermelho na hora, e subi-la é trabalho anterior a ligar o gate.

### Contagem de classes de teste

```console
$ find services/api/src/test -name '*Test.java' | wc -l
76
```

São **76** classes, não 68 como o `README.md:78` afirmava. (Na §7, depois dos testes novos, são
**77 classes**.)

---

## 3. Passo 2 — mobile: `typecheck`, `lint`, `test`

```console
$ cd apps/mobile && npm run typecheck
> tsc --noEmit
### EXIT=0

$ npm run lint
> eslint .
...
✖ 15 problems (0 errors, 15 warnings)
  0 errors and 4 warnings potentially fixable with the `--fix` option.
### EXIT=0

$ npm test
Test Suites: 18 passed, 18 total
Tests:       225 passed, 225 total
Snapshots:   0 total
Time:        5.716 s, estimated 6 s
### EXIT=0
```

São **225 testes em 18 suítes**, não 221 em 17. As 18 são as suítes que o `npm test` roda; os dois
arquivos `*.e2e.test.ts` ficam fora por `testPathIgnorePatterns` em `jest.config.js`, de propósito.

**São 15 avisos e ZERO erros, e o build passa** — mas os avisos existem e nenhum `CLAUDE.md` os
menciona. Por regra:

| Regra | Qtd | O que é |
|---|---|---|
| `@typescript-eslint/no-require-imports` | 8 | `require()` em mocks de teste, onde `import` não serve |
| *Unused eslint-disable directive* (`no-console`) | 3 | `renderizacao.test.tsx:76,117,141` desligam `no-console` onde a regra **não dispararia** — a medição da F15 imprime por `console.log`, mas a regra não está ligada para arquivos de teste. São diretivas inúteis, não `console.log` proibidos |
| `import/no-named-as-default-member` | 2 | `axios.create` / `axios.isAxiosError` acessados pelo default |
| React Compiler, *Compilation Skipped* | 1 | `app/(app)/missao/criar.tsx:80` usa API que devolve funções, então o componente não é memoizado — consequência de desempenho de render, não de correção |
| `import/first` | 1 | import no corpo do módulo em `acessibilidade.test.tsx` |

Nenhum deles vem das mudanças desta entrega.

---

## 4. Passo 3 — git, e a caça a segredo

```console
$ git status --short
?? docs/auditoria/entrega-final.md

$ git diff --cached --stat
(vazio — nada staged)
```

O único arquivo fora do índice é o relatório de auditoria que acompanha esta evidência. **Nada está
staged, logo não há diff staged onde haver segredo** — e por isso o hook `checar-segredo.sh`, que só
age sobre `git commit`, não tinha o que examinar. Isto não é o mesmo que "não há segredo no
repositório": é só o que este passo consegue afirmar.

---

## 5. Passo 4 — contêiner

```console
$ make ps
NAME           IMAGE                              COMMAND      SERVICE   CREATED             STATUS             PORTS
omnitribo-db   docker.io/postgis/postgis:16-3.5   "postgres"   db        About an hour ago   Up About an hour   0.0.0.0:5432->5432/tcp
```

Um único serviço, `healthy`, na 5432. O schema e o seed desta sessão foram aplicados no boot do
backend às 22:42: **34 migrations**, sendo 27 de schema (`V1`–`V8` e `V11`–`V29`) e 7 de seed
(`V900`–`V906`).

---

## 6. Resumo

| | Testes | Falhas | Erros | Pulados |
|---|---|---|---|---|
| Backend — JUnit 5 · Testcontainers · ArchUnit, **76 classes** | **755** | 0 | 0 | 2 |
| Mobile — Jest · RTL · MSW, **18 suítes** | **225** | 0 | 0 | 0 |

Verde também em: Spotless (419 arquivos), SpotBugs (`effort=Max`, `threshold=Medium`,
`failOnError=true` — `BugInstance size is 0`) e os **dois** gates JaCoCo.

---

## 7. Segunda execução, em 2026-09-13, depois de fechar três buracos de teste

A auditoria que esta evidência acompanha apontou três buracos em que a suíte **inteira** continuava
verde sob sabotagem. Eles foram fechados com três casos novos, e a suíte foi reexecutada. Os números
da §6 são os da entrega auditada; estes são os de agora, e são os que o `README.md` publica.

```console
[INFO] Tests run: 768, Failures: 0, Errors: 0, Skipped: 2
[INFO] All coverage checks have been met.
[INFO] All coverage checks have been met.
[INFO] Spotless.Java is keeping 420 files clean - 0 needs changes to be clean
[INFO] BugInstance size is 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:35 min

$ cd apps/mobile && npm run typecheck && npm test
Test Suites: 18 passed, 18 total
Tests:       226 passed, 226 total
```

Cobertura: **92,78 %** global (18 655 / 20 107) e **92,61 %** em `dominio` (11 449 / 12 363), sobre
`INSTRUCTION`. E `BRANCH` **subiu de 76,37 % para 77,15 %** (888 / 1 151) — os casos novos exercitam
ramos que nenhum teste alcançava. Eles são de regra, não de linha nova: prendem comportamento que já
estava coberto por execução e não por asserção, que é exatamente a distinção que a §1 da seção
seguinte faz.

| Buraco | Teste que o fecha | Sabotagem, e o que ela provou |
|---|---|---|
| Nenhum teste afirmava que uma transição avança `estado_desde` — todos sobrescreviam a coluna por SQL para montar cenário | `MissaoStateMachineTest.matrizCompletaDeTransicoes` (as 17 válidas e as 82 recusadas) + `missaoAntigaRecemTransicionadaNaoParecerParadaHaDias` | Removido `this.estadoDesde = quando;` de `Missao.java:357`: **18 falhas**. Antes, a suíte inteira passava |
| A ordem do painel de recusas não tinha teste: o caso existente assere sobre `conteudo[0]` com **um** grupo só na resposta | `DespachanteAlertaOperacionalTest.painelOrdenaPelaLojaQueRecusouMais` | `ORDER BY COUNT(*) DESC` → `ASC`: o teste novo falha, e `consultaDeRecusasDevolveAContagemReal` **continua verde** — era o buraco |
| `RecusaFiltroRequest` era o único dos três endpoints ADMIN novos sem teste de validação | `painelDeRecusasRecusaJanelaEPaginacaoForaDosLimites`, incluindo a borda `horas=720` que tem de **passar** | — (a garantia existia; faltava a asserção) |
| A fixture do painel de impacto é internamente consistente, então aceitava o app recalcular o total no cliente | `impacto.test.tsx` — *"os três totais VÊM do servidor"*, com fixture **deliberadamente inconsistente** | `total: ce.menos50Brl` → `ce.reentregasEvitadas * premissa * 0.5`: **só o teste novo falha**; o de "três variações lado a lado" continua verde |

A coluna da sabotagem é a entrega de verdade: cada uma foi **executada**, e em três dos quatro casos
o teste que já existia permaneceu verde — que é a definição de buraco disfarçado de cobertura.

---

## O que esta evidência **não** garante

Escrito antes de alguém precisar perguntar.

1. **Não garante que os testes falham quando deveriam.** 755 verdes dizem que o código passa nas
   asserções que existem, não que as asserções prendem o comportamento. A revisão de testes desta
   mesma entrega achou três buracos em que a suíte **inteira** continua verde sob sabotagem — o mais
   grave sendo que nenhum teste afirma que uma transição de missão avança `estado_desde`, porque todos
   sobrescrevem a coluna por SQL para montar o cenário. Cobertura de 92,68 % é cobertura de execução,
   não de garantia.
2. **Não mede desempenho, e o tempo aqui não é comparável com nenhum outro.** `Total time: 01:32 min`
   foi obtido nesta máquina, com cache de build quente, sem registro de governor nem de frequência de
   CPU — que é justamente a razão pela qual o índice deste diretório proíbe comparar latência entre
   execuções. Para carga, a referência continua sendo `f21-carga.md`.
3. **Não inclui `npm run test:e2e`**, de propósito: aquele teste exige o backend em execução e um
   endereço de rede que varia por máquina. Ele **não** foi executado nesta verificação.
4. **Não inclui a varredura de dependências** (OWASP Dependency-Check), que vive no profile
   `seguranca` e exige chave da NVD. Ela **nunca concluiu**, nem local nem no CI — o gate está
   configurado e o resultado não existe.
5. **Não prova ausência de segredo no repositório.** A §4 afirma só que não havia diff staged para
   examinar. O `gitleaks` do CI, que varre o histórico completo, é quem faz essa afirmação.
6. **Não prova portabilidade.** Foi medido em Linux (Fedora) com podman rootless, Node v24.19.0 e
   JDK 21.0.12 Temurin. O CI usa Node 22; macOS, Windows e Docker Desktop não foram exercitados.
7. **BRANCH em 76,37 % é o número real e não há gate sobre ele.** Está aqui como evidência para ler,
   não como garantia cumprida.
