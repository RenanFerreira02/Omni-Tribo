---
name: verificar
description: Roda a verificação completa e reporta o estado real. Use antes de abrir PR.
allowed-tools: Bash, Read, Grep
---

Execute e cole a saída REAL de cada comando (não resuma, não presuma):

0. Se o diff tocou `db/migration` ou `db/seed`: `make reset` ANTES do passo 1.
   O seed vive na faixa 900, então migration nova nasce out-of-order e o boot falha com
   "Detected resolved migration not applied to database" — mensagem que não menciona seed nem
   ordenação. Seed alterado muda o checksum e falha com "Migration checksum mismatch".
1. cd services/api && ./mvnw -q verify
2. Mobile — SOMENTE se apps/mobile/package.json existir:
   cd apps/mobile && npm run typecheck && npm run lint && npm test
   Se o arquivo não existir, o app mobile ainda não foi iniciado (F9+): reporte este passo como
   NÃO VERIFICADO e siga. Não é falha, e não invalida os demais passos.
3. git status --short e git diff --cached procurando segredo
4. docker compose ps

Depois responda: o que está VERDE (com evidência), o que está VERMELHO (com a mensagem exata), e o
que NÃO foi verificado e por quê. Se algo falhar, NÃO corrija por conta própria: reporte e espere
instrução. Nunca declare sucesso sem ter executado.
