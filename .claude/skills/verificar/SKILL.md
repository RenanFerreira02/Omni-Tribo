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
2. cd apps/mobile && npm run typecheck && npm run lint && npm test
   O `test:e2e` fica FORA daqui de propósito: exige o backend em execução e um endereço de rede que
   varia por máquina (ver apps/mobile/README.md). Rode-o à mão quando for validar integração real.
3. git status --short e git diff --cached procurando segredo
4. docker compose ps

Depois responda: o que está VERDE (com evidência), o que está VERMELHO (com a mensagem exata), e o
que NÃO foi verificado e por quê. Se algo falhar, NÃO corrija por conta própria: reporte e espere
instrução. Nunca declare sucesso sem ter executado.
