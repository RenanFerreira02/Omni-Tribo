## O que este PR faz

_1-3 frases._

## Checklist

- [ ] `./mvnw verify` verde (backend) e/ou `npm run typecheck && npm run lint && npm test` (mobile)
- [ ] Nenhum segredo, `.env` ou chave no diff
- [ ] Decisão relevante registrada em `docs/DECISOES.md`
- [ ] Endpoint novo com teste de caminho feliz **e** de erro
- [ ] Controller só recebe/devolve DTO; erro sai como `ProblemDetail` com `type`

## Evidências

_Saída real do terminal._
