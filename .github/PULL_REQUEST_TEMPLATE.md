## O que este PR faz

_Descreva em 1-3 frases o que foi implementado ou corrigido._

## Checklist

- [ ] `./mvnw verify` verde (backend) ou `npm test` verde (mobile)
- [ ] Cobertura não caiu em relação à branch base
- [ ] `git diff` revisado — nenhum segredo, `.env`, chave ou credencial no diff
- [ ] ADR criado em `docs/adr/` se houve decisão arquitetural relevante
- [ ] `docs/PROGRESSO.md` atualizado (Status, PR, Data)
- [ ] Campos de DTO validados com Bean Validation (backend) ou Zod (mobile)
- [ ] Nenhuma entidade JPA exposta diretamente em controller — apenas DTO/record
- [ ] Erros retornam RFC 9457 `ProblemDetail` (nunca stack trace ou mensagem de driver)
- [ ] Novos endpoints têm teste de caminho feliz **e** de erro

## Evidências

_Cole saída do terminal (teste, build, screenshot) ou link para `docs/evidencias/`._
