# Evidência — histórico real do CI no GitHub Actions

**Data:** 2026-08-17
**Fase:** F13 (entrega final) — verificação do pipeline
**Fonte:** API pública do GitHub Actions, repositório `RenanFerreira02/Omni-Tribo`

Esta evidência existe porque três afirmações do projeto eram sobre o **CI** e nenhuma delas apontava
para execução: o ✅ do Gitleaks na matriz de rastreabilidade vinha do YAML e não de um run, a frase
"o CI do mobile ficou vermelho da F9 até 2026-08-13" não tinha registro, e **nenhum documento
registrava que o `Security Scan` estava vermelho desde 2026-08-15**.

## Comando

Não há `gh` na máquina de desenvolvimento; o repositório é público, então a API responde sem
autenticação. As páginas foram coletadas em blocos de 30 (`per_page=100` devolveu *504 Gateway
Time-out* de forma intermitente):

```bash
for p in 1 2 3 4; do
  curl -s --max-time 40 \
    "https://api.github.com/repos/RenanFerreira02/Omni-Tribo/actions/runs?per_page=30&page=$p" \
    -o "pg$p.json"
done

python3 - <<'PY'
import json, glob
rs = []
for f in sorted(glob.glob('pg*.json')):
    rs += json.load(open(f))['workflow_runs']
rs = sorted({r['id']: r for r in rs}.values(), key=lambda x: x['created_at'])
for r in rs:
    print(r['created_at'][:19], r['id'], r['head_sha'][:7], r['head_branch'], r['conclusion'])
PY
```

Detalhe por job (o que identifica *qual* job falhou dentro do workflow):

```bash
curl -s "https://api.github.com/repos/RenanFerreira02/Omni-Tribo/actions/runs/<RUN_ID>/jobs"
```

## Universo coletado

```
runs unicos: 113
periodo: 2026-08-04 -> 2026-08-16

Security Scan  total= 48  success= 43  failure=  5
Mobile CI      total= 23  success= 14  failure=  9
API CI         total= 42  success= 34  failure=  8
```

`total_count` reportado pela API: **113**. Coletados: **113**.

## 1. O `Security Scan` estava vermelho — e desde quando

As quatro últimas execuções, todas com o mesmo desfecho:

```
2026-08-15T18:58:09Z | 31902589012 | ca328fc | develop                | failure
2026-08-16T12:22:55Z | 31946902605 | 6202eb3 | docs/f13-entrega-final | failure
2026-08-16T12:23:13Z | 31946915781 | 6202eb3 | docs/f13-entrega-final | failure
2026-08-16T12:23:32Z | 31946928136 | 3acc7bb | develop                | failure
```

A execução imediatamente anterior, em `114b517`, foi **verde**:

```
2026-08-15T12:14:04Z | 31884082951 | 114b517 | develop                | success
```

O commit que separa as duas é **`ca328fc`** ("feat: Resiliência, testes e gates"), que introduziu o
job `dependencias` no `security.yml`. **O job falhou em 100% das execuções desde que existe (4/4).**

### Qual job falhou

```
=== run 31946928136 ===
  gitleaks       -> success
      passo 3: success em 2s  | Gitleaks — varre histórico completo
  dependencias   -> failure
      passo 4: success em 0s  | Cache da base do Dependency-Check
      passo 5: failure em 25s | OWASP Dependency-Check — reprova em alta e crítica
      passo 6: success em 0s  | Upload relatório de vulnerabilidades

=== run 31946902605 ===
  gitleaks       -> success
      passo 3: success em 3s  | Gitleaks — varre histórico completo
  dependencias   -> failure
      passo 5: failure em 36s | OWASP Dependency-Check — reprova em alta e crítica

=== run 31946915781 ===
  gitleaks       -> success
      passo 3: success em 2s  | Gitleaks — varre histórico completo
  dependencias   -> failure
      passo 5: failure em 26s | OWASP Dependency-Check — reprova em alta e crítica

=== run 31902589012 ===
  gitleaks       -> success
  dependencias   -> failure
      passo 5: failure em 25s | OWASP Dependency-Check — reprova em alta e crítica
```

**Dois números fecham o diagnóstico sem precisar do log:**

1. **25 a 36 segundos.** O Dependency-Check baixa a base da NVD e analisa o classpath inteiro; isso
   leva minutos, não segundos. Meio minuto é o tempo de o Maven subir e o plugin abortar na
   aquisição de dados.
2. **O passo "Upload relatório de vulnerabilidades" concluiu com sucesso em 0s.** O
   `actions/upload-artifact` tinha `if-no-files-found` no padrão (`warn`), então **passou sem
   encontrar arquivo nenhum** — ou seja, o Dependency-Check não chegou a produzir relatório. O passo
   que existia para dar visibilidade era o que escondia a falha.

A causa é a ausência do secret `NVD_API_KEY`: o plugin recebe string vazia e aborta com
`NvdApiException: Invalid API Key, length of 0`. O mesmo erro foi capturado localmente e está colado
em [`../qualidade/verificacao-2026-08-15.md`](../qualidade/verificacao-2026-08-15.md) (§5).

### O que mudou em 2026-08-17

O job `dependencias` saiu do gatilho de `push`/`pull_request` e passou a rodar em `schedule` semanal
e `workflow_dispatch`, com o passo de varredura guardado por `if: env.NVD_API_KEY != ''` e um passo
que emite `::warning` quando a chave falta. O `gitleaks` continua em todo push e PR. Ver
`.github/workflows/security.yml` e as Notas de manutenção do
[`../PROGRESSO.md`](../PROGRESSO.md).

**Isto não faz a varredura de dependências passar a existir.** Ela continua sem ter rodado até o
fim, e nenhuma afirmação sobre CVE é sustentada por este projeto.

## 2. O Gitleaks executa e passa — sustentação por run, não por YAML

Nas 48 execuções do `Security Scan`, o job `gitleaks` concluiu **`success` em todas**, inclusive nas
quatro em que o workflow inteiro ficou vermelho por causa do outro job (acima). Ele varre o
histórico completo (`fetch-depth: 0`) em 2 a 3 segundos.

Isso substitui a evidência que a matriz de rastreabilidade citava para o requisito 15, que era o
próprio trecho de configuração do YAML.

> Única exceção no histórico: o run `31122420005` (2026-08-06, `feat/f4-ciclo-vida-missoes`) aparece
> como `failure`. A reexecução do mesmo SHA `10860ec` 90 segundos depois (`31122500510`) foi verde.
> Falha transitória de runner, não achado de segredo — mas fica registrada aqui, porque omitir o
> ponto fora da curva é o começo de uma evidência desonesta.

## 3. O CI do mobile ficou vermelho da F9 até 2026-08-13

Afirmado em [`../COMPARATIVO-TECNOLOGIAS.md`](../COMPARATIVO-TECNOLOGIAS.md) sem registro até aqui.
O histórico completo do `Mobile CI`:

```
2026-08-08T22:25:18Z | 31281649784 | 3e7e68e | feat/front-end-mobile     | success
2026-08-09T10:39:52Z | 31308878686 | f2f3936 | feat/front-end-mobile     | failure   <- comeca
2026-08-09T10:40:24Z | 31308902413 | f2f3936 | feat/front-end-mobile     | failure
2026-08-09T10:40:44Z | 31308916067 | 02d7caa | develop                   | failure
2026-08-09T16:22:44Z | 31323629003 | a6bdfd1 | develop                   | failure
2026-08-09T20:14:53Z | 31333764591 | e7b131e | develop                   | failure
2026-08-09T21:26:56Z | 31336852911 | 6d2f1ce | develop                   | failure
2026-08-10T00:27:40Z | 31344510508 | da1ed70 | develop                   | failure
2026-08-11T07:42:28Z | 31470064289 | 01a3771 | develop                   | failure
2026-08-13T07:36:57Z | 31678496585 | 479ab38 | chore/verificacao-mobile  | failure   <- ultima vermelha
2026-08-13T23:47:30Z | 31755093815 | 5f6fc11 | chore/verificacao-mobile  | success   <- conserto
2026-08-13T23:50:56Z | 31755299463 | 5f6fc11 | chore/verificacao-mobile  | success
2026-08-13T23:51:33Z | 31755336641 | 2067072 | develop                   | success
```

**Nove execuções vermelhas seguidas**, de 2026-08-09 a 2026-08-13, viradas no commit `5f6fc11`
(branch `chore/verificacao-mobile`). Verde nas 13 execuções seguintes, até a última (`3acc7bb`).
A afirmação do comparativo está correta e agora é verificável.

## 4. Estado na entrega

Última execução de cada workflow, no merge do PR #14 (`3acc7bb`, 2026-08-16):

| Workflow | Run | Desfecho |
|---|---|---|
| API CI | `31946928160` | ✅ success |
| Mobile CI | `31946928092` | ✅ success |
| Security Scan | `31946928136` | ❌ failure (job `dependencias`; `gitleaks` verde) |

As oito falhas históricas do `API CI` são todas de 2026-08-06 e 2026-08-07 (F4 e F5, durante o
desenvolvimento) e nenhuma é posterior a `a3edef7`.

## O que esta evidência **não** prova

- **Não contém nenhum log de execução.** A API pública devolve *conclusão* de run, job e passo sem
  autenticação, mas o download de log exige token com escopo `actions:read` — pedi e recebi `403`.
  O que está provado é qual job e qual passo falharam, e em quanto tempo; a linha
  `Invalid API Key, length of 0` vem da reprodução **local** em
  [`../qualidade/verificacao-2026-08-15.md`](../qualidade/verificacao-2026-08-15.md), não do runner.
- **Não prova ausência de segredo no repositório.** Prova que o Gitleaks rodou e não reprovou, com a
  configuração e o conjunto de regras padrão que ele traz. Regra não escrita é segredo não procurado.
- **Não prova ausência de CVE.** Nunca houve varredura de dependências concluída — nem no CI nem
  localmente.
- **Não prova que o conserto de 2026-08-17 funciona.** A primeira execução do workflow corrigido é
  posterior a este arquivo; quando ela ocorrer, o resultado entra aqui numa revisão datada.
- **Não diz nada sobre cobertura ou qualidade dos testes que o CI executa** — isso está em
  [`f13-make-test.md`](f13-make-test.md) e em [`../qualidade/`](../qualidade/).
