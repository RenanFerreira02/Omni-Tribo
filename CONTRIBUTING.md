# Contribuindo com o Omni-Tribo

## Conventional Commits

Todos os commits devem seguir o padrão [Conventional Commits](https://www.conventionalcommits.org/):

```
<tipo>(<escopo opcional>): <descrição curta em português>
```

Tipos utilizados neste projeto:

| Tipo       | Quando usar |
|------------|-------------|
| `feat`     | Nova funcionalidade |
| `fix`      | Correção de bug |
| `test`     | Adição ou correção de testes |
| `refactor` | Refatoração sem mudança de comportamento |
| `docs`     | Documentação (ADRs, README, PROGRESSO.md) |
| `chore`    | Tarefas de manutenção (deps, build, CI) |
| `ci`       | Mudanças no pipeline de CI/CD |

Exemplos:

```
feat(missoes): adicionar endpoint de aceite de missão
fix(carteira): corrigir condição de corrida no crédito de BRL
test(identidade): adicionar teste de token expirado
docs(adr): registrar decisão sobre três moedas (0004)
```

## Branches

- Padrão: `feat/fN-nome-curto` — ex: `feat/f1-identidade`, `feat/f6-geolocalizacao`
- **Nunca commitar diretamente na `main`**
- Uma branch por fase; fases paralelas usam sufixo descritivo

## Checklist pré-commit

Antes de cada commit, verifique:

1. `./mvnw verify` passa (backend) ou `npm run typecheck && npm test` passa (mobile)
2. `git diff --cached` não contém segredos, `.env`, chaves ou credenciais — o hook
   `.claude/hooks/checar-segredo.sh` faz uma segunda passada e **nega** o commit, mas ele olha só o
   que está staged; quem varre o histórico é o gitleaks no CI (ver a seção abaixo)
3. Nenhum `@Disabled`, `skip: true` ou `console.log` de debug esquecido

## Quando o gitleaks reprovar

O `gitleaks` roda em **todo push e PR**, sem filtro de caminho, sobre o **histórico completo**
(`fetch-depth: 0`) — porque um segredo commitado e removido depois continua alcançável no histórico,
e é exatamente esse o caso que uma varredura só do `HEAD` não pega. Está verde desde sempre, e é o
candidato a status obrigatório de merge.

Quando ele reprovar, a ordem importa mais que os passos.

### 1. Rotacione o segredo ANTES de mexer no git

**Esta é a inversão que mais se erra.** Se o commit chegou ao GitHub, o segredo vazou — reescrever a
árvore não o desvaza. O repositório pode ser privado, mas o valor já passou por logs de CI, por
cache de runner e pela API do GitHub, e forks e clones locais mantêm o objeto.

Revogue e emita outro **primeiro**. Só depois cuide do histórico. Um histórico limpo com a
credencial ainda válida é a pior combinação possível: parece resolvido e não está.

Neste projeto, o que costuma vazar e como rotacionar:

| Segredo | Onde vive | Como rotacionar |
|---|---|---|
| Chaves RSA do JWT | `services/api/keys/` (gitignored) | `bash tools/gerar-chaves-dev.sh` — invalida toda sessão emitida |
| `app.webhooks.segredos.*` | `.env` / variável de ambiente | combine o novo com a transportadora antes de trocar |
| Senha do Postgres | `.env`, a partir de `.env.example` | `make reset` recria o volume |
| `NVD_API_KEY` | secret do GitHub | revogue em nvd.nist.gov e gere outra |

### 2. Reescreva o histórico — `git revert` NÃO resolve

`revert` cria um commit novo que desfaz o conteúdo; o blob com o segredo continua no histórico e o
gitleaks continua reprovando, corretamente.

```bash
# Ainda não empurrou: reescreva localmente
git rebase -i <commit-anterior-ao-vazamento>     # ou `git commit --amend`, se for o último

# Já empurrou: reescreva o histórico e force o push
pipx install git-filter-repo                     # ou: pip install --user git-filter-repo
git filter-repo --invert-paths --path caminho/do/arquivo-com-segredo
# alternativa, quando o segredo está no meio de um arquivo que deve continuar existindo:
git filter-repo --replace-text <(echo 'valor-do-segredo==>REMOVIDO')
```

> `git push --force` está na lista de coisas que **exigem pedido explícito** (ver `CLAUDE.md`,
> Regras não negociáveis). Vazamento de segredo é um dos poucos casos em que ele é a ação certa —
> combine antes com quem mais tiver clone, porque o histórico reescrito quebra o deles.

Depois de reescrever, rode a varredura localmente antes de empurrar de novo:

```bash
gitleaks detect --source . --log-opts="--all"
```

### 3. `.gitleaksignore` — quando é legítimo, e quando é carimbo

Só para **falso positivo comprovado**: chave de exemplo em documentação, fixture de teste,
`.env.example`, hash que o detector confundiu com credencial.

A disciplina é a mesma do `services/api/dependency-check-suppressions.xml`, e pelo mesmo motivo:
**supressão silenciosa transforma o gate num carimbo.** Toda entrada leva comentário dizendo por que
aquilo não é segredo, e quem escreveu.

```
# 2026-08-24 — Chave RSA de EXEMPLO no docs/seguranca/autenticacao.md, gerada para a
# documentação e nunca usada por nenhum ambiente. Não rotacionável porque não autentica nada.
docs/seguranca/autenticacao.md:rsa-private-key:42
```

**Nunca** suprima para "destravar o PR". Se o segredo é real, o passo 1 é rotacionar.

### 4. As duas barreiras locais que rodam antes do CI

- **`.claude/hooks/checar-segredo.sh`** — `PreToolUse`/`Bash`, dispara em qualquer comando que
  contenha `git commit`. Faz grep no `git diff --cached` por chave PEM, chave `AIza…` e pares
  `senha|password|secret|token|api_key = "…"`. **Nega a operação.**
- O item 2 do checklist acima, que é a leitura humana do diff.

Nenhuma das duas substitui o gitleaks: elas olham o que está **staged**, ele olha o **histórico
inteiro**.

### 5. Tornar o check obrigatório

Ainda não está — é configuração do repositório no GitHub, não do código:

1. **Settings → Branches → Add branch ruleset** (ou *Add rule*, na interface antiga)
2. Alvo: `main`
3. Marque **Require status checks to pass before merging**
4. Procure e selecione **`gitleaks`** (o nome do job em `.github/workflows/security.yml`)
5. Marque também **Require branches to be up to date before merging**

O job `dependencias`, do mesmo workflow, **não** deve entrar como obrigatório — e o motivo escrito
aqui até 2026-08-24 estava errado, embora a conclusão estivesse certa.

Dizia-se que ele "nunca produziria status num PR, e um check obrigatório que não roda trava todo
merge". Não é o caso: `dependencias` é pulado por **`if:` de job**, dentro de um workflow cujo
gatilho é amplo e que **roda** em todo push e PR. Job pulado por `if:` **reporta sucesso e satisfaz
o check** — é exatamente a regra que o cabeçalho de `api.yml` registra, e a razão de os filtros
`paths:` terem virado o job `mudou`. Quem trava o PR em *"Expected — Waiting for status to be
reported"* é o **workflow** pulado por `paths:`, que é outra coisa.

O motivo real de mantê-lo fora é o inverso: ele **não** travaria merge nenhum. Ficaria verde em todo
PR sem ter varrido dependência alguma — a mesma falsa garantia que o `::warning` daquele workflow
existe para tornar visível. Um check obrigatório que passa por não ter medido é pior que check
nenhum, porque compra confiança sem entregar verificação.

---

## Como criar um ADR

Quando uma decisão arquitetural relevante for tomada:

1. Copie `docs/adr/TEMPLATE.md` para `docs/adr/NNNN-titulo-da-decisao.md`
2. Incremente `NNNN` em relação ao último ADR existente
3. Preencha todas as seções — especialmente **Alternativas descartadas**
4. Marque o item correspondente no checklist do PR
