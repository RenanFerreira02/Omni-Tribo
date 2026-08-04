---
name: verificar
description: Roda a verificação completa e reporta o estado real. Use antes de abrir PR.
allowed-tools: Bash, Read, Grep
---

Execute e cole a saída REAL de cada comando (não resuma, não presuma):

1. cd services/api && ./mvnw -q verify
2. cd apps/mobile && npm run typecheck && npm run lint && npm test
3. git status --short e git diff --cached procurando segredo
4. docker compose ps

Depois responda: o que está VERDE (com evidência), o que está VERMELHO (com a mensagem exata), e o
que NÃO foi verificado e por quê. Se algo falhar, NÃO corrija por conta própria: reporte e espere
instrução. Nunca declare sucesso sem ter executado.
