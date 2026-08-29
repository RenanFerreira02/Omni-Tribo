# Fluxo econômico — quem financia, onde o token nasce, onde ele morre

Três moedas, e só uma circula no ciclo de missões.
Ver [ADR 0009](../adr/0009-economia-do-cuidado-token-como-recompensa.md).

| Moeda | Papel | Circula? |
|---|---|---|
| **XP** | reputação — deriva o nível, filtra elegibilidade | não é transferível, não tem ledger, só cresce |
| **TOKEN** | moeda comunitária — recompensa de **todas** as categorias | sim, transferível dentro da tribo |
| **BRL** | **fora do ciclo de missões** | `ck_missao_economia` exige `valor_brl = 0` em toda missão |

**A premissa que governa tudo: quem cria a missão NÃO paga.** A recompensa é calculada pelo servidor
e congelada na criação — o DTO de criação não tem `xpRecompensa` nem `tokensRecompensa`.

---

## Por onde o TOKEN entra e sai

```mermaid
flowchart TB
    subgraph conserva ["✅ CONSERVA — o token só muda de dono"]
        direction TB
        membros["👥 Membros da tribo<br/><i>carteira debitada</i>"]
        pote["🫙 missao.pote_tokens<br/><i>custódia</i>"]
        exec1["🧑‍🔧 Executor<br/><i>carteira creditada</i>"]
        membros -->|"POST /tribos/{id}/financiamentos<br/>FINANCIAMENTO_TRIBO"| pote
        pote -->|"CONCLUIDA · RECOMPENSA_MISSAO"| exec1
        pote -->|"CANCELADA ou EXPIRADA<br/>ESTORNO"| membros
    end

    subgraph cunha ["⚠️ CUNHA — o token nasce do nada"]
        direction TB
        nada(("∅"))
        exec2["🧑‍🔧 Executor<br/><i>carteira creditada</i>"]
        nada -->|"CONCLUIDA · RECOMPENSA_MISSAO"| exec2
    end

    tribo["TRIBO"] --> conserva
    coleta["COLETA"] --> conserva
    entrega["ENTREGA"] --> cunha
    ajuda["AJUDA"] --> cunha

    patro["🏢 Carteira de patrocinador<br/><b>NÃO IMPLEMENTADA</b>"]
    patro -.->|"aresta que fecha o ciclo<br/>(Pendência #1)"| pote

    exec1 --> resgate["🎁 Resgate em benefício<br/>de parceiro do bairro"]
    exec2 --> resgate
    resgate -.->|"sumidouro real<br/><b>ainda não existe no backend</b>"| nada2(("∅"))

    style cunha fill:#fff1f0,stroke:#c0392b
    style conserva fill:#eefaf3,stroke:#1f6f4a
    style patro fill:#f5f5f5,stroke:#999,stroke-dasharray: 5 5
    style resgate fill:#fdf6e3,stroke:#b58900
```

## O que o diagrama admite

**As duas arestas tracejadas são o que falta**, e estão desenhadas de propósito.

**1. O patrocinador não existia — e passou a existir.** Até 2026-08-20, ENTREGA e AJUDA cunhavam.
Medido do zero em 2026-08-16: um ciclo AJUDA aumentou `SUM(saldos) + SUM(potes)` em exatamente o
valor da recompensa, enquanto um ciclo TRIBO financiado deixou a soma parada
([evidência de época](../evidencias/f13-conservacao-por-categoria.md)).

Aquilo **não tinha sido contornado por esquecimento**. Exigir pote para ENTREGA faria membros da
tribo custearem a logística do varejista — o inverso do modelo. O financiador correto é o
patrocinador: entrega que falhou custa re-entrega, armazenagem e risco de perder o cliente, então
patrocinar o pote sai mais barato que o fracasso. É esse o caso de negócio, e preferiu-se **uma
lacuna documentada a uma regra errada codificada** enquanto ele não estava implementado.

**Hoje está.** A carteira de patrocinador chegou no [ADR 0024](../adr/0024-carteira-de-patrocinador.md)
(`V23`), AJUDA passou a pagar do pote no [ADR 0025](../adr/0025-ajuda-paga-do-pote.md), e o resgate
virou o sumidouro no [ADR 0027](../adr/0027-resgate-queima-token.md). A emissão saiu da conclusão e
virou um ponto só, `APORTE_PATROCINADOR`. Medição de 2026-08-22: **Δ=0 nas quatro categorias**
([evidência](../evidencias/f14-conservacao-quatro-categorias.md)).

O que **ainda** cunha é ENTREGA criada por humano — sem transportadora, não há patrocinador a
debitar. Ela é `FontePote.CUNHAGEM`, declarada na linha da missão.

**2. O resgate não tem sumidouro no backend.** O catálogo de benefícios é dado local do app: não há
tabela de parceiro, endpoint, nem motivo `RESGATE` no ledger. Simular o débito no cliente produziria
um saldo que o servidor desmente no primeiro `refetch` — a tela diz ao usuário que a baixa ainda não
acontece.

## O multiplicador de risco amplia a cunhagem — de forma limitada e deliberada

Missão nascida de entrega falida recebe multiplicador ∈ **[1,00; 1,50]**, congelado na linha junto
com `versao_formula`. Como ENTREGA cunha, risco alto cunha até 1,5× o que cunharia.

**É exatamente por causa da Pendência #1 que o teto é estreito**, existe em dois blocos de
configuração e tem um teste (`CoerenciaTetoRiscoTest`) travando a concordância entre eles. Sem teto,
o risco multiplicaria a emissão sem financiador.

O multiplicador entra na **BASE** do cálculo, junto da complexidade — nunca sobre o total.
Multiplicar o total escalaria também distância, peso e volume, e a recompensa explodiria de forma
não linear no caso extremo.

## As duas invariantes, que não são a mesma

```mermaid
flowchart LR
    R["<b>RECONCILIAÇÃO</b><br/>por carteira:<br/>saldo == SUM(lançamentos)<br/><br/>✅ tem endpoint<br/>GET /admin/carteiras/reconciliacao"]
    C["<b>CONSERVAÇÃO</b><br/>no sistema:<br/>SUM(saldos) + SUM(potes) constante<br/><br/>❌ não tem endpoint"]
    R -.->|"cunhar escreve OS DOIS LADOS,<br/>então isto continua verde"| C
    style R fill:#eefaf3,stroke:#1f6f4a
    style C fill:#fff1f0,stroke:#c0392b
```

Uma invariante que ninguém mede não está garantida. A história de como o projeto descobriu isso está
em [`../EVOLUCAO-ARQUITETURAL.md`](../EVOLUCAO-ARQUITETURAL.md).
