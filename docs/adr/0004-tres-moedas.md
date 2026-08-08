# 0004 — Três Moedas com Papéis Distintos

**Data:** 2026-08-04  
**Status:** Substituído por [0009](./0009-economia-do-cuidado-token-como-recompensa.md)

---

## Contexto

O app precisa recompensar usuários de formas diferentes dependendo do tipo de missão:
missões de entrega e ajuda envolvem dinheiro real; missões comunitárias e de coleta envolvem
moeda local da tribo. Além disso, o sistema de progressão de usuário (níveis, ranking) exige
uma métrica de reputação imutável que não seja transferível nem sacável.

---

## Decisão

Adotamos três moedas com papéis e implementações distintas:

> ⚠️ **A linha do BRL abaixo foi REVOGADA pelo [ADR 0009](./0009-economia-do-cuidado-token-como-recompensa.md).**
> A premissa de que o criador paga a missão em dinheiro nunca foi a do produto: quem publica não
> paga. Hoje `ck_missao_economia` (V15) exige `valor_brl = 0` em **todas** as categorias, e a
> recompensa é XP + TOKEN. As linhas de **XP** e **TOKEN** seguem válidas, com uma mudança: TOKEN
> passou a ser a recompensa de todas as categorias, não só de TRIBO e COLETA.

| Moeda | Papel | Transferível | Ledger | Tipo SQL | Missões elegíveis |
|-------|-------|-------------|--------|----------|-------------------|
| **XP** | Reputação e progressão de nível | Não | Não (coluna monotônica em `usuario`) | `integer` | Todas |
| **BRL** | Dinheiro real | Não (apenas saque via gateway externo) | Sim — ACID rigoroso, append-only | `numeric(12,2)` | ENTREGA, AJUDA |
| **TOKEN** | Moeda comunitária da tribo | Sim (dentro da mesma tribo) | Sim — ACID rigoroso, append-only | `bigint` | TRIBO, COLETA |

**Regra de negócio invariante:** missão do tipo `TRIBO` ou `COLETA` não pode ter `valor_brl > 0`.
Esta regra é validada no domínio (`Missao.validarEconomia()`) e verificada por teste unitário.

**Por que XP sem ledger:** XP é estritamente monotônico e não financeiro. Um ledger seria
overhead sem benefício — não há necessidade de auditoria de transação nem de estorno de XP.
Um contador simples em `usuario.xp_total` é suficiente e elimina a superfície de ataque de
manipulação retroativa de pontos.

---

## Consequências

**Positivas:**
- Separação clara de papéis evita bugs do tipo "usuário sacou XP como dinheiro".
- `numeric(12,2)` para BRL previne erros de ponto flutuante em operações financeiras.
- `bigint` para TOKEN evita overflow em sistemas com alta emissão comunitária.
- Ledger append-only para BRL e TOKEN garante auditabilidade completa; correções são por estorno.

**Negativas / trade-offs:**
- Três sistemas de recompensa aumentam a complexidade do domínio de carteira.
- A regra de elegibilidade de missão (BRL ↔ ENTREGA/AJUDA; TOKEN ↔ TRIBO/COLETA) precisa ser
  verificada em múltiplos pontos: criação de missão, aceite e crédito.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Moeda única (pontos genéricos) | Não reflete a tese de produto: recompensas em dinheiro real para entregas vs. moeda comunitária para mutirões são experiências intencionalmente diferentes. |
| BRL e TOKEN unificados como "créditos" | Impossível auditar separadamente, e cria confusão sobre o que pode ser sacado em dinheiro real. |
| XP com ledger | Overhead sem benefício: XP não é financeiro, não tem estorno, e não precisa de trilha de auditoria por transação. |
