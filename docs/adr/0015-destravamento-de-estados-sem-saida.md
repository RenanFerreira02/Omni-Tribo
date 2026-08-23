# 0015 — Estados sem saída: varredura por prazo e porta de ADMIN

**Data:** 2026-08-11
**Status:** Aceito

---

## Contexto

Dois estados da máquina não tinham saída que não dependesse de uma pessoa específica aparecer:

- **`EM_ANDAMENTO`** tinha UMA transição: `CHECKIN`, do executor. Se o executor abandonasse, nem ele,
  nem o criador, nem um ADMIN tinham qualquer transição disponível.
- **`AGUARDANDO_CONFIRMACAO`** tinha duas — `CONFIRMAR` e `CONTESTAR` —, **ambas do criador**. Se o
  criador sumisse, a missão parava indefinidamente. E o ADMIN não podia entrar: `EM_DISPUTA`, o único
  estado onde ele atua, só se alcança por `CONTESTAR`, que também é do criador.

O job de expiração varria apenas `status = ABERTA`.

Consequência: o `pote_tokens` de uma missão TRIBO/COLETA ficava **em custódia permanente**. E a perda
era invisível pelo endpoint criado para achá-la — a reconciliação compara ledger com projeção, e as
duas continuam batendo com tokens presos numa missão morta. Quem quebra é a **conservação**
(`SUM(carteiras) + SUM(potes)`), que é outra invariante. É a mesma classe de perda que
`EstornoFinanciamentoService` descreve como "economicamente idêntico a queimar dinheiro de terceiros".

---

## Decisão

Cada um dos dois estados ganhou **duas** saídas: varredura automática por prazo (ator `SISTEMA`) e
porta manual (`POST /missoes/{id}/destravar`, só ADMIN, com justificativa obrigatória na trilha).

**Os desfechos são deliberadamente diferentes:**

| Situação | Transição | Desfecho econômico |
|---|---|---|
| Executor abandonou (sem check-in) | `EM_ANDAMENTO --EXPIRAR_EXECUCAO--> EXPIRADA` | **Estorna** o pote |
| Criador sumiu (após o check-in) | `AGUARDANDO_CONFIRMACAO --EXPIRAR_CONFIRMACAO--> CONCLUIDA` | **PAGA** o executor |
| Caso excepcional, decisão humana | `--DESTRAVAR--> CANCELADA` | **Estorna** o pote |

**Por que pagar quando o criador some.** Houve check-in — geolocalizado, validado 100% no servidor, e
que é a evidência de presença que o sistema aceita como prova em todo outro caminho. Expirar
estornando puniria quem executou por uma omissão do outro lado. Uma plataforma de cuidado que deixa o
executor sem receber quando o criador desaparece destrói exatamente a confiança que é a tese do
produto.

Passa por `CONCLUIDA`, então a regra "só `CONCLUIDA` credita" fica intacta e o pagamento reusa
`concluirComCredito` — o único caminho de crédito do sistema.

**Três tipos de trilha distintos**, não um `EXPIRADA` genérico: `EXECUCAO_EXPIRADA`,
`CONFIRMACAO_EXPIRADA` e `DESTRAVADA_POR_ADMIN`. São três causas com desfechos econômicos diferentes,
e um tipo único tornaria a reconciliação incapaz de explicar por que um pote voltou.

A regra de varredura virou **dado** (`RegraExpiracao`): acrescentar um estado passa a ser uma linha de
configuração mais uma transição, não um job novo.

Coluna nova `missao.estado_desde` (V20), mantida pela máquina de estados: nenhum marco existente
responde "há quanto tempo esta missão está parada AQUI" — `janela_fim` é o prazo da OFERTA, `aceita_em`
é zerado no DESISTIR, `criada_em` é imóvel.

---

## Consequências

**Positivas:**
- Nenhum estado não-terminal depende de um humano específico para ser deixado. É a regra nova, e está
  escrita no javadoc de `StatusMissao` para valer em estados futuros.
- Zero caminho de valor novo: `aplicar` já estornava em CANCELADA/EXPIRADA, `pagaTokensDoPote` já
  creditava em CONCLUIDA. Isso é o que torna a mudança segura — dois caminhos de crédito é como a
  conservação se perde sem ninguém perceber.
- ~~`MissaoRepository.potesImobilizados` dá visibilidade ao dinheiro que a reconciliação não
  acha.~~ **Retificado em 2026-08-20.** Esta consequência nunca foi verdade: a consulta foi escrita
  e **nenhum serviço, endpoint ou teste jamais a chamou**, então a visibilidade prometida não
  existiu em momento nenhum. A query foi removida como órfã na varredura de
  [`varredura-orfaos.md`](../auditoria/varredura-orfaos.md) §2.1, e a lacuna real — não há como
  achar pote imobilizado — está registrada como **Pendência #2** do `CLAUDE.md` (a numeração encolheu três vezes, conforme F8 e os ADRs 0026 e 0028 fecharam pendências anteriores). O resto desta
  decisão (varredura por prazo + porta de ADMIN) continua valendo e está implementado.

**Negativas / trade-offs:**
- Pagar por omissão do criador aceita um risco: conluio, ou check-in sem execução real. A documentação
  de antifraude já registra que nenhum dos dois é detectável — e um criador distraído confirmaria do
  mesmo jeito. A porta de escape é o ADMIN.
- Os prazos (48h execução, 72h confirmação) são calibração, não verdade. Erram para o lado longo de
  propósito: prazo curto tira a missão de quem está executando de boa-fé; prazo longo só adia a
  devolução de um pote que, antes, ficaria preso para sempre.
- A máquina passou de 13 para 17 transições, e a matriz de teste de 99 para 126 combinações.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Reusar `EXPIRAR` para os três casos | A trilha perderia a distinção entre "janela venceu", "executor abandonou" e "criador sumiu" — três desfechos econômicos diferentes viram um registro só, e a reconciliação não explica mais por que o pote voltou. |
| `AGUARDANDO_CONFIRMACAO` expirando com estorno (não pagando) | Pune quem executou por uma omissão do criador. O check-in é a evidência que o sistema aceita em todo outro caminho; ignorá-la só aqui seria incoerente, e a mensagem ao executor seria "faça o trabalho e torça". |
| Só a porta de ADMIN, sem varredura | Depende de alguém olhar. No intervalo, o pote fica imobilizado e invisível — que é exatamente o problema. |
| Só a varredura, sem porta de ADMIN | Não há saída para o caso que a regra de prazo não previu (missão legítima em disputa silenciosa, acordo fora da plataforma). O único desfecho possível seria esperar o prazo e aceitar o que ele decidir. |
| Inferir o marco temporal lendo `missao_evento` em vez de criar `estado_desde` | Um JOIN por linha dentro do laço do job, para responder uma pergunta que uma coluna responde em O(1) com índice parcial. |
