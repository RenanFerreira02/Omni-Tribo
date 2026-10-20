# Economia

## Três moedas

| Moeda | Papel | Transferível | Onde mora |
|---|---|---|---|
| **XP** | reputação; o **nível é derivado** do XP por `RegraNivel`, nunca incrementado | não | `usuario.xp` (sem ledger, monotônico) |
| **TOKEN** | moeda do bairro: recompensa de toda missão, resgatável em benefício de parceiro | sim, dentro da tribo | `carteira.saldo_tokens` + ledger `lancamento` |
| **BRL** | fora do ciclo de missões (`ck_missao_economia` exige `valor_brl = 0`) | — | infraestrutura inerte (`SaqueService`, saque desligado) |

Token **não** é conversível em real, nem nos dois sentidos. O valor que a transportadora oferta
entra na fórmula só como insumo de calibração.

## Regras

- **Quem cria a missão não paga** (0009, imposta pela 0037: `financiador == criador` → 422).
- **A recompensa é calculada pelo servidor e congelada** (`CalculadoraDeRecompensa`, função pura).
  - Insumos: categoria, complexidade, distância, peso, volume e multiplicador de risco.
  - Os números ficam em `app.missoes.recompensa.*`. **Mudou o YAML? Suba `versao`**:
    `CalculadoraDeRecompensaTest.douradoV1` falha de propósito para forçar isso.
  - `POST /missoes/previa-recompensa` mostra o valor sem criar nada, e o app nunca duplica a
    fórmula.
- **Complexidade:** ENTREGA e COLETA derivam de peso e volume; TRIBO e AJUDA declaram.
- **PATCH recalcula** a recompensa quando um insumo vem no corpo (0036). A guarda do pote é
  bidirecional.
- **`CONCLUIDA` é o único estado que credita.**

## De onde sai o token de cada missão — `missao.fonte_pote`

Congelada na criação:

| Fonte | Quem | Publicar exige | Na conclusão |
|---|---|---|---|
| `COMUNIDADE` | TRIBO, COLETA, AJUDA | pote financiado por **outros** membros cobrindo a recompensa | paga do pote |
| `SEM_TOKEN` | TRIBO/AJUDA "só XP" | nada | só XP, nenhum lançamento |
| `PATROCINADOR` | missão de retirada do webhook | — (nasce com o pote cheio) | paga do pote |
| `CUNHAGEM` | ENTREGA criada por humano | nada | **emite** o token |

Cancelar ou expirar **estorna** o pote aos financiadores. Isso acontece em dois pontos:
`MissaoService.aplicar` e `ExpiracaoMissoesService.expirarUma`. O estorno enxerga os dois motivos
(`FINANCIAMENTO_TRIBO` e `FINANCIAMENTO_PATROCINADOR`).

## A invariante

`SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)` só muda em três pontas:

| Ponta | Efeito |
|---|---|
| `APORTE_PATROCINADOR` (ADMIN, auditado, idempotente) | sobe |
| conclusão de ENTREGA com `fonte_pote = CUNHAGEM` | sobe (implícito) |
| `RESGATE` | desce (queima) |

Todo o resto só move token de lugar.

## Dois instrumentos, duas perguntas

- `GET /admin/carteiras/reconciliacao` → `integro`: o saldo de cada carteira bate com o ledger
  dela? **Não detecta emissão**: cunhar escreve os dois lados.
- `potesImobilizados` (mesmo endpoint, campo separado) e `GET /admin/missoes/potes-imobilizados`:
  há token preso em missão parada? `integro=true` com pote imobilizado é estado coerente.

## Concorrência

Toda operação de valor segue **locks → sondagem de `Idempotency-Key` → regras → escrita**.

- Ordem global dos locks: `missao` → `carteira` (id crescente) → `usuario`.
- Saldo só muda por `LivroRazaoService`.
- `REQUIRES_NEW` é proibido no caminho de valor: a transação interna pediria uma segunda conexão
  com a externa segurando `FOR UPDATE`, e isso dá deadlock de pool.
