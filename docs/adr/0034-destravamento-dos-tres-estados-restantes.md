# 0034 — Destravamento dos três estados não-terminais restantes

**Data:** 2026-09-13
**Status:** Aceito

---

## Contexto

O [ADR 0015](0015-destravamento-de-estados-sem-saida.md) enunciou a regra que o javadoc de
`StatusMissao` carrega desde então:

> todo estado não-terminal precisa de saída que **não** dependa de um humano específico aparecer.

Ele aplicou a regra a `EM_ANDAMENTO` e `AGUARDANDO_CONFIRMACAO`, que eram os dois becos conhecidos na
época. O que ninguém enunciou é que a regra valia para **três dos seis** estados não-terminais, e não
para todos.

A lacuna apareceu ao implementar o diagnóstico do [ADR 0032](0032-diagnostico-de-pote-imobilizado.md):
o instrumento passou a listar missão não-terminal com `pote_tokens > 0` parada, e cada linha traz
`varreduraCobre`. Foi ao ler `varreduraCobre=false` que o mapa ficou visível.

**O pote existe nos SEIS estados não-terminais.** `FinanciamentoService.validarEstado` recusa
financiamento só em estado TERMINAL, em `CUNHAGEM` e em `PATROCINADOR` — e um comentário in-line
afirma, corretamente, que **RASCUNHO é financiável**: publicar missão comunitária exige pote cobrindo
a recompensa, então o financiamento acontece *antes* da publicação.

| Estado | Varredura por prazo | Porta de ADMIN (antes deste ADR) |
|---|---|---|
| `RASCUNHO` | não | **nenhuma** |
| `ABERTA` | sim (`janela_fim`) | dispensável |
| `ACEITA` | não | **nenhuma** |
| `EM_ANDAMENTO` | sim (48 h) | `destravar` |
| `AGUARDANDO_CONFIRMACAO` | sim (72 h) | `destravar` |
| `EM_DISPUTA` | não | `resolver` |

Em `RASCUNHO` e `ACEITA` a única saída era o criador ou o executor agir. Se a pessoa desaparecia, o
pote ficava preso e **nem um ADMIN conseguia soltá-lo**. `EM_DISPUTA` tinha porta — `resolver` —, mas
ela obriga o ADMIN a *julgar o mérito* (concluir pagando, ou cancelar), e há casos em que não há mérito
a julgar: a disputa ficou sem informação, as duas partes sumiram, e o que se quer é liberar o pote sem
declarar vencedor.

A perda é do tipo que o instrumento de reconciliação **não** vê: ledger e projeção continuam batendo
com tokens presos numa missão morta. Quem quebra é a conservação, que é outra invariante — o mesmo
raciocínio do ADR 0015 e do
[`EVOLUCAO-ARQUITETURAL.md`](../EVOLUCAO-ARQUITETURAL.md). O ADR 0032 deu um instrumento **detectivo**
para ela; este ADR dá o **corretivo** que faltava.

Não é hipotético: a auditoria de entrega final de 2026-09-12 encontrou, no banco de demonstração, uma
missão `ACEITA` com **42 tokens presos** e nenhuma porta capaz de soltá-los.

---

## Decisão

**`DESTRAVAR` passa a ser aceito em `RASCUNHO`, `ACEITA` e `EM_DISPUTA`, indo para `CANCELADA`.**

A máquina de estados vai de **17 para 20 transições**.

Três propriedades fazem esta decisão ser de configuração da máquina, e não de caminho de valor novo:

1. **`aplicar()` já estorna em `CANCELADA`** (`MissaoService:1089`). Nenhuma linha de código de
   movimentação de token nasce daqui — o que muda é quais origens alcançam aquele destino.
2. **A autorização vem de graça.** `DESTRAVAR` é declarado `AtorEsperado.ADMIN` em `EventoMissao:59`,
   e a autorização é resolvida por EVENTO, não por par (origem, evento). Estender a transição não
   abre a porta para mais ninguém.
3. **`DESTRAVADA_POR_ADMIN` já está no CHECK da V20.** Nenhuma migration é necessária, e a trilha
   `missao_evento` aceita o tipo desde então.

A justificativa continua obrigatória e continua indo para o payload da trilha: destravar é ato
discricionário, e ato discricionário sem motivo registrado é o que torna uma auditoria posterior
impossível.

---

## Consequências

**Positivas**

- Os **seis** estados não-terminais passam a ter saída que não depende de um humano específico. A
  regra do javadoc de `StatusMissao` deixa de valer para três dos seis e passa a valer para todos, o
  que a torna verificável em vez de aspiracional.
- O `varreduraCobre=false` do diagnóstico do ADR 0032 deixa de significar "ninguém alcança isto" e
  passa a significar "nenhuma varredura alcança, mas um ADMIN alcança". É a diferença entre lacuna e
  procedimento.
- A conservação ganha um caminho corretivo completo: detectar (ADR 0032) e agir (este).

**Negativas, e elas são reais**

- **A matriz de `MissaoStateMachineTest` cresce, e ela é escrita à mão de propósito.** São 99
  combinações (9 status × 11 eventos) e a tabela esperada é independente do enum justamente para que
  o teste não concorde com a remoção de uma transição. Três entradas novas significam três linhas
  novas escritas à mão — e é esse custo que garante que o teste continue sendo antídoto contra
  tautologia. Quem acrescentar transição no futuro paga o mesmo.
- **`RASCUNHO → CANCELADA` agora tem dois caminhos**: `CANCELAR` (do criador) e `DESTRAVAR` (do
  ADMIN). Os dois são legítimos e produzem o mesmo destino, mas gravam tipos de trilha diferentes
  (`MISSAO_CANCELADA` × `DESTRAVADA_POR_ADMIN`), o que é o desejado: quem leu a trilha precisa saber
  se foi o dono ou uma intervenção.
- **`EM_DISPUTA` passa a ter três saídas para o ADMIN**, e duas delas cancelam. `RESOLVER_CANCELAR` e
  `DESTRAVAR` chegam a `CANCELADA` pelo mesmo estorno. A diferença é semântica e fica na trilha:
  `RESOLVER_CANCELAR` é julgamento de mérito ("a disputa foi decidida contra o executor"),
  `DESTRAVAR` é desistência de julgar ("não há informação para decidir; libere o pote"). Um ADMIN que
  não souber a diferença escolherá errado, e o único remédio é o texto da justificativa. **Não há
  como o código impedir essa confusão** — é a consequência negativa que este ADR aceita.
- Nada disto torna o destravamento automático. Continua sendo **consulta ativa mais ação humana**:
  `GET /admin/missoes/potes-imobilizados` para achar, `POST /missoes/{id}/destravar` para agir. É o
  quarto instrumento que depende de alguém olhar, e quatro instrumentos passivos continuam não
  somando um alarme.

---

## Alternativas descartadas

**1. Estender a varredura por prazo a `RASCUNHO` e `ACEITA`, em vez de dar porta de ADMIN.**
Recusada porque os dois estados não têm prazo natural a medir. `ABERTA` tem `janela_fim`, que é uma
promessa feita ao executor; `EM_ANDAMENTO` e `AGUARDANDO_CONFIRMACAO` medem por `estado_desde` contra
um prazo calibrado. Um rascunho, por definição, é trabalho em andamento do criador — expirá-lo por
tempo apagaria missão que alguém está escrevendo. E expirar `ACEITA` por prazo puniria o executor que
aceitou e ainda não iniciou, que é exatamente a janela que `INICIAR` existe para cobrir.

**2. Só `RASCUNHO` e `ACEITA`, deixando `EM_DISPUTA` como está.**
Foi a leitura inicial, e ela é defensável: `EM_DISPUTA` **tem** porta de ADMIN. Recusada porque
`resolver` obriga a julgar o mérito, e o caso que motiva este ADR é justamente o que não tem mérito
julgável. Deixar `EM_DISPUTA` fora manteria um caso em que o ADMIN tem de escolher entre pagar alguém
que talvez não mereça e cancelar contra alguém que talvez mereça, quando o que ele quer é só não
decidir.

**3. Um endpoint novo, `POST /missoes/{id}/liberar-pote`, que estorna sem mudar status.**
Recusada por violar a máquina de estados: o pote é atributo da missão, e uma missão não-terminal sem
pote depois de ter tido pote é um estado que nenhuma transição produz. Pior, ela voltaria a ser
publicável sem pote — reabrindo o beco que `validarPoteSuficienteParaPublicar` fecha. A máquina de
estados existe para que toda mudança de dinheiro tenha uma transição correspondente na trilha.

**4. Deixar a lacuna documentada e não corrigir.**
Era o estado anterior, e tem um argumento honesto: a lacuna é visível no diagnóstico, e três
transições novas mexem na peça mais sensível do domínio. Recusada porque a lacuna **já se
materializou** com 42 tokens presos no próprio banco de demonstração, e porque o custo medido é uma
configuração de enum mais três linhas de tabela de teste — não um caminho de valor novo.
