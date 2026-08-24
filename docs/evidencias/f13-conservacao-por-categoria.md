> **SUPERADA em 2026-08-22 por [`f14-conservacao-quatro-categorias.md`](./f14-conservacao-quatro-categorias.md).**
>
> Este documento mede um estado que não existe mais. Ele afirma que `MissaoService.pagaTokensDoPote`
> cobre só TRIBO e COLETA e que AJUDA cunha token na conclusão — as duas coisas deixaram de ser
> verdade com o [ADR 0024](../adr/0024-carteira-de-patrocinador.md) (patrocinador financia o pote da
> entrega falida) e o [ADR 0025](../adr/0025-ajuda-paga-do-pote.md) (AJUDA paga do pote como TRIBO).
> O script que ele cita foi reescrito para cinco ciclos e não reproduz mais esta saída.
>
> Fica no repositório como registro histórico: é a medição que documentou a lacuna enquanto ela
> existia, e é contra ela que a F14 mostra o que mudou.

# Conservação do TOKEN por categoria — e por que a reconciliação não a enxerga

**Data:** 2026-08-16 · **Fase:** F13 · **Ambiente:** banco recriado do zero (`make reset`), backend
no perfil `dev`, PostgreSQL 16.9 + PostGIS 3.5 em container.

Esta evidência existe para sustentar uma afirmação específica de
[`../EVOLUCAO-ARQUITETURAL.md`](../EVOLUCAO-ARQUITETURAL.md): **reconciliação e conservação são
invariantes diferentes, e o sistema só mede uma delas.** A auditoria F7 já havia medido isso em
2026-08-08; aqui a medição é refeita do zero, com o script versionado abaixo, para que possa ser
reproduzida na banca.

## As duas invariantes

| | Definição | Tem endpoint? |
|---|---|---|
| **Reconciliação** | para cada carteira, `saldo` == `SUM(lançamentos)` | **Sim** — `GET /api/v1/admin/carteiras/reconciliacao` |
| **Conservação** | `SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)` constante no ciclo | **Não** |

A reconciliação compara a **projeção** contra o **ledger**. Cunhar token escreve os **dois lados** —
um lançamento de crédito e o saldo correspondente —, então ela continua batendo. A conservação é
sobre o *total do sistema*, e nenhuma consulta a verifica.

## Método

Dois ciclos completos de missão, ponta a ponta por HTTP (nenhum `UPDATE` manual), medindo a soma
antes e depois de cada um:

- **Ciclo 1 — AJUDA:** alice cria → publica → bob aceita → inicia → check-in → alice confirma.
  AJUDA não paga do pote (`MissaoService.pagaTokensDoPote` cobre só TRIBO e COLETA).
- **Ciclo 2 — TRIBO:** bob cria → **carol financia o pote** → publica → carol aceita → inicia →
  check-in → bob confirma.

Script: [`../../tools/evidencias/conservacao-por-categoria.sh`](../../tools/evidencias/conservacao-por-categoria.sh)

## Saída real

```
############ BASELINE ############
carteiras|potes|total = 689|156|845
reconciliação inicial: {"integro":true,"divergencias":0}

############ CICLO 1 — AJUDA (esperado: CUNHAGEM, Δ > 0) ############
prévia: 30 tokens, 90 XP
criada: 1d6f4348-6b27-497a-8b66-6e06f5ff125b status=RASCUNHO tokens=30 pote=0
publicar: ABERTA
aceitar:  ACEITA
iniciar:  EM_ANDAMENTO
checkin:  AGUARDANDO_CONFIRMACAO
confirmar:CONCLUIDA
--> conservação antes=845  depois=875  Δ=30   (recompensa da missão: 30)
--> reconciliação: {"integro":true,"divergencias":0}

############ CICLO 2 — TRIBO financiada (esperado: CONSERVAÇÃO, Δ = 0) ############
prévia: 38 tokens
criada: 80da7bd8-1753-4a2b-b149-3711480a26c1 status=RASCUNHO
tribo de bob: aaaaaaaa-0000-0000-0000-000000000002
financiamento por carol: {"poteTokens":38}
  (conservação logo após financiar: 681|194|875)
publicar: ABERTA
aceitar:  ACEITA
iniciar:  EM_ANDAMENTO
checkin:  AGUARDANDO_CONFIRMACAO
confirmar:CONCLUIDA
--> conservação antes=875  depois=875  Δ=0   (recompensa da missão: 38)
--> reconciliação: {"integro":true,"divergencias":0}

############ RESUMO ############
AJUDA  Δ=30  recompensa=30
TRIBO  Δ=0  recompensa=38
reconciliação final: {"integro":true,"divergencias":0}
lançamentos por motivo:
BONUS = 4
FINANCIAMENTO_TRIBO = 5
RECOMPENSA_MISSAO = 10
```

## Leitura

**1. A cunhagem é exatamente do tamanho da recompensa.** AJUDA: Δ = 30, recompensa = 30. Não é
arredondamento nem efeito colateral — os 30 tokens que bob recebeu não saíram de lugar nenhum.

**2. O financiamento não cria nem destrói nada, só move.** A linha intermediária mostra o mecanismo:
`681|194|875`. A carteira de carol caiu 38 e o pote da missão subiu 38; o total ficou parado em 875.
Quando a missão conclui, o pote paga carol de volta e o total continua 875. **Δ = 0.**

**3. A reconciliação respondeu `integro=true` nos dois casos** — inclusive no ciclo que cunhou 30
tokens do nada. É a demonstração literal da tese: uma invariante que ninguém mede não está
garantida, e ter um endpoint de integridade verde não significa que o sistema esteja íntegro no
sentido que importa.

## Escopo desta evidência

- Mede **TOKEN**. BRL está fora do ciclo de missões por construção (`ck_missao_economia` exige
  `valor_brl = 0`), então não há o que conservar ali.
- ENTREGA cunha pelo mesmo caminho de AJUDA (`pagaTokensDoPote` retorna `false` para as duas); o
  ciclo de ENTREGA não foi refeito aqui porque a missão de ENTREGA nasce do webhook e envolve ponto
  de custódia — a auditoria F7 já mediu esse caso e registrou **Δ = +60** com reconciliação íntegra.
- **Isto não é um defeito escondido.** É a Pendência #1 do [`../../CLAUDE.md`](../../CLAUDE.md), com
  razão de produto documentada: exigir pote para ENTREGA faria membros da tribo custearem a logística
  do varejista. O financiador correto é o patrocinador, e a mecânica que fecha isso
  (`FinanciamentoMissao`) já existe.
