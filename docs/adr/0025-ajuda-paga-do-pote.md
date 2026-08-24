# 0025 — AJUDA paga do pote como TRIBO: o argumento do varejista nunca foi sobre ela

**Data:** 2026-08-21
**Status:** Aceito
**Retifica parcialmente:** [0024](./0024-carteira-de-patrocinador.md) §8

---

## Contexto

O ADR 0024 fechou a cunhagem de ENTREGA e deixou duas categorias em `FontePote.CUNHAGEM`: AJUDA e
ENTREGA criada por humano. O §8 daquele ADR tratou as duas como o mesmo problema e usou, para as
duas, uma variação do mesmo argumento:

> AJUDA não tem financiador plausível: quem pede ajuda não paga — é a premissa do produto —, e
> exigir pote da tribo faria o vizinho custear o favor que ele mesmo pediu.

**Aquele argumento está errado, e o erro é de conflação.** Ele mistura duas coisas distintas:

1. **"Quem cria a missão NÃO paga"** (ADR 0009) — que continua valendo integralmente;
2. **"A comunidade não deve financiar"** — que é uma afirmação diferente, e que em TRIBO já é falsa.

Em TRIBO o criador também não paga: o pote é formado por **outros** membros da tribo, e é exatamente
por isso que `FinanciamentoService` permite RASCUNHO ser financiado por qualquer membro (o javadoc de
`validarEstado` diz que restringir ao criador "obrigaria uma pessoa só a bancar 100% da missão,
matando o co-financiamento que é o propósito da moeda comunitária"). O "vizinho custeando o favor que
ele mesmo pediu" descreve um cenário que a regra de TRIBO nunca produziu.

O argumento que de fato justifica manter ENTREGA cunhando é outro, e é específico dela: **existe um
varejista do outro lado.** Entrega que falhou custa re-entrega e armazenagem a uma transportadora, e
fazer vizinhos pagarem por isso é o inverso do modelo. AJUDA não tem varejista. É missão entre
vizinhos, como TRIBO.

---

## Decisão

**Adotamos para AJUDA exatamente a regra de TRIBO.** `Missao` passa a derivar `FontePote.COMUNIDADE`
para AJUDA, o que faz duas coisas de uma vez, sem tocar em nenhuma delas diretamente:

- `MissaoService.pagaTokensDoPote` passa a pagar AJUDA do pote (ele lê `fonte_pote` desde a V23);
- `validarPoteSuficienteParaPublicar` passa a exigir pote cobrindo a recompensa antes de publicar.

E `FinanciamentoService.validarEstado` deixou de listar categorias: passou a testar a **fonte**.

**Nenhuma migration.** A coluna `fonte_pote` já aceita `'COMUNIDADE'` desde a V23; o que muda é qual
valor o construtor deriva.

O ADR 0009 não muda: quem cria continua não pagando. O pote de uma AJUDA é formado por outros
membros da tribo, e `validarAutorizacao` continua exigindo que financiador e criador sejam da mesma
tribo.

---

## O que NÃO mudou, e é onde o cuidado esteve

**Os dois pontos de estorno já cobriam AJUDA, e isso foi verificado, não presumido.** `MissaoService`
(`aplicar`) e `ExpiracaoMissoesService` (`expirarUma`) chaveiam por `missao.getPoteTokens() > 0`,
nunca por categoria — assim como `EstornoFinanciamentoService.estornarPote`, que devolve pelo
`missaoId`. Nenhuma linha precisou mudar ali.

Mas a cobertura era **teórica até agora**: nenhuma AJUDA jamais teve pote para estornar. Os testes
novos (`cancelarAjudaEstorna` e `expirarAjudaEstorna`) são a primeira vez que esse caminho é
exercitado com AJUDA, e o segundo deles atravessa deliberadamente `ExpiracaoMissoesJob.varrer` — o
caminho que NÃO passa por `aplicar()` e que o `CLAUDE.md` registra como o fácil de esquecer.

**Missões AJUDA que já existem no banco continuam com `fonte_pote = 'CUNHAGEM'.** Não há UPDATE, e a
ausência é deliberada: elas foram criadas quando AJUDA cunhava, não têm pote, e marcá-las como
COMUNIDADE faria a conclusão delas falhar com 422 para sempre. O corte é por data de criação — mesmo
critério que a V905 usou para as entregas falidas anteriores ao patrocinador.

---

## Consequências

**Positivas:**

- A conservação passa a valer para **três das quatro categorias** criadas por usuário. Só ENTREGA
  criada por humano ainda cunha, e ela tem uma razão nomeada e específica.
- `ConservacaoTokensTest` teve a assertion de AJUDA **apertada** — de `delta == recompensa` para
  `delta == 0`. É a segunda vez que o ramo que cunha encolhe por ali, e as duas vezes a mudança foi
  no sentido de exigir mais.
- `validarEstado` testar a FONTE em vez de listar categorias elimina uma classe de bug: incluir uma
  categoria no construtor e esquecer a lista a deixaria **impublicável e infinanciável ao mesmo
  tempo** — exigindo pote para publicar e recusando todo financiamento que o formasse, sem nenhum
  erro apontando a causa.

**Negativas / trade-offs:**

- **AJUDA deixa de ir ao ar na hora.** Um vizinho que peça ajuda fica em RASCUNHO até alguém
  financiar o pote. Antes a missão era publicada de imediato e a recompensa era cunhada na conclusão.
  É a mudança de comportamento mais visível para o usuário final, e o app precisa explicá-la.
- **Há uma diferença real em relação a TRIBO que este ADR não resolve:** num mutirão, quem financia
  também se beneficia (é bem coletivo); numa AJUDA o beneficiário é uma pessoa só. Se financiar favor
  alheio se mostrar pouco atraente na prática, AJUDA fica represada em RASCUNHO. Não é problema
  técnico e não muda o desenho — é hipótese de adoção, e o dado para testá-la não existe (não há
  operação).
- Nenhum token fica preso por causa disso: `RASCUNHO --CANCELAR--> CANCELADA` já é saída com estorno.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Manter AJUDA cunhando, como o §8 do ADR 0024 decidiu | O argumento que sustentava aquela decisão é sobre varejista e não se aplica a AJUDA. Mantê-la cunhando exigiria um argumento novo, e não há um. |
| Deixar o criador financiar a própria AJUDA | Violaria o ADR 0009 ("quem cria a missão NÃO paga"), que esta mudança existe para preservar — e reproduziria exatamente o cenário que o §8 do 0024 temia. |
| `validarEstado` continuar listando categorias, só somando AJUDA | Duas listas descrevendo a mesma regra (o construtor e o validador) divergem em silêncio na próxima mudança. Testar `fonte_pote` faz a regra ter um dono só. |
| Migration marcando as AJUDAs existentes como COMUNIDADE | Elas não têm pote. A conclusão passaria a falhar com 422 para sempre, e num caminho que nem sempre é uma requisição de usuário. |
| Fazer o mesmo com ENTREGA criada por humano | Fora do escopo desta tarefa, e o argumento do varejista continua valendo para ela. Segue como a última lacuna de cunhagem, declarada em `FontePote.CUNHAGEM`. |
