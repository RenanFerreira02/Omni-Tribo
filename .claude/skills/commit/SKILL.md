---
name: commit
description: Aplica o checklist pré-commit do CONTRIBUTING.md e monta a mensagem Conventional Commit. Uso /commit ou /commit só o módulo carteira
disable-model-invocation: true
allowed-tools: Bash, Read, Grep
---

Preparar commit. Recorte pedido: $ARGUMENTS (vazio = tudo que está modificado)

Execute na ordem e **cole a saída real** de cada passo. Não presuma, não resuma.

## 1. Ver o que vai entrar

```bash
git status --short && git diff --stat
```

Confirme que o recorte bate com o que foi pedido. Se houver arquivo modificado que não pertence a
este commit, diga qual e pergunte antes de incluir.

## 2. Verificar

Se o diff toca `services/api/`:

```bash
cd services/api && ./mvnw -q verify
```

Se toca `db/migration` ou `db/seed`, rode `make reset` ANTES do `verify` — migration nova nasce
out-of-order num banco de dev existente (ver a skill `/migration`).

Se `apps/mobile/package.json` existir e o diff tocar `apps/mobile/`:
`cd apps/mobile && npm run typecheck && npm test`.

**Falhou? Pare.** Não relaxe assertion, não adicione `@Disabled`, não commite mesmo assim. Reporte a
mensagem exata e espere instrução.

## 3. Caçar o que não deveria ser commitado

```bash
git diff --cached
git diff --cached | grep -nE '@Disabled|console\.log|System\.out\.print|TODO: ?remover|skip: ?true'
```

E confirme, olhando o diff: nenhum segredo, `.env`, chave PEM, credencial ou token. O hook
`checar-segredo.sh` é uma segunda barreira, não a revisão — ele pega padrão conhecido, não pega
segredo com formato novo.

## 4. Montar a mensagem

Formato: `<tipo>(<escopo>): <descrição curta em português>`

Tipos aceitos: `feat` · `fix` · `test` · `refactor` · `docs` · `chore` · `ci`
Escopo: o módulo tocado — `missoes`, `carteira`, `identidade`, `geolocalizacao`, `logistica`,
`notificacoes`, `compartilhado` — ou `adr`, `infra`, `ci` quando não for código de módulo.

O corpo, quando houver, explica o **porquê**, não o quê: o diff já mostra o quê. Mudança de
segurança, concorrência ou transação sempre leva corpo — esse código precisa ser defensável
oralmente numa banca.

## 5. Commitar

Confira a branch antes: `git rev-parse --abbrev-ref HEAD`. **Nunca commite direto em `main`.** Padrão
de branch é `feat/fN-nome-curto`; se estiver em `main`, crie a branch antes.

Mostre a mensagem final e espere aprovação antes de rodar `git commit`. Não faça `push` a menos que
eu peça.
