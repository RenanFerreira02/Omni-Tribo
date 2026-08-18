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
2. `git diff --cached` não contém segredos, `.env`, chaves ou credenciais
3. Nenhum `@Disabled`, `skip: true` ou `console.log` de debug esquecido

## Como criar um ADR

Quando uma decisão arquitetural relevante for tomada:

1. Copie `docs/adr/TEMPLATE.md` para `docs/adr/NNNN-titulo-da-decisao.md`
2. Incremente `NNNN` em relação ao último ADR existente
3. Preencha todas as seções — especialmente **Alternativas descartadas**
4. Marque o item correspondente no checklist do PR
