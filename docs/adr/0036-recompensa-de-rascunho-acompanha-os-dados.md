# 0036 — A recompensa acompanha os dados; o que a publicação fecha é o POTE, não o valor

**Data:** 2026-09-22
**Status:** Aceito — **revisado no mesmo dia, antes do merge.** Ver "Revisão" no fim.
**Depende de:** [0035](./0035-missao-comunitaria-sem-recompensa-em-token.md)

---

## Contexto

O ADR 0035 tornou `recompensaEmToken` uma escolha do criador. A escolha mais valiosa que existe nele
não é a da criação — é a da **edição**: quem já criou uma missão comunitária e está preso no 422 de
pote insuficiente precisa poder trocar para "só XP" e publicar. Sem isso, o ADR 0035 resolveria o
problema só para missões futuras, e deixaria os rascunhos represados exatamente onde estão.

Tornar o campo editável obriga a decidir o que o `PATCH` faz com a recompensa congelada. Hoje ele
não recalcula nada, e o javadoc de `Missao.editarRascunho` justifica isso assim:

> Recompensa (BRL, tokens, XP), categoria, criador, executor e status ficam deliberadamente de fora:
> alterar a recompensa com a missão **já ABERTA** mudaria o contrato sob os pés de quem está prestes
> a aceitar, e status/executor só mudam pela máquina de estados.

**O argumento é bom e cobre só metade do intervalo.** `PATCH` é aceito em RASCUNHO **e** em ABERTA
(`MissaoStateMachine.validarEdicao`), e em RASCUNHO não há contrato nenhum: ninguém viu a missão,
ninguém a aceitou, e ela sequer aparece em listagem alheia — a consulta esconde rascunho de terceiro
dentro do próprio SQL. A frase "sob os pés de quem está prestes a aceitar" descreve ABERTA, e foi
aplicada aos dois estados sem que a diferença fosse examinada.

A consequência de não examinar: **o `PATCH` já altera `pesoKg`, `volumeL` e as coordenadas — que são
insumos da fórmula — sem recalcular nada.** Uma missão passava a ter dados que não explicam a própria
recompensa, com `versao_formula` afirmando que explicam. Isso existe desde que o PATCH existe; o que
muda agora é que a edição ganha UI e deixa de ser teórica.

---

## Decisão

**`PATCH` em `RASCUNHO` recongela a recompensa a partir dos dados resultantes. `PATCH` em `ABERTA`
continua sem tocá-la.**

A linha divisória é a PUBLICAÇÃO, porque é ela que torna a missão visível e aceitável. Antes dela a
recompensa é um rascunho de valor; depois, é promessa.

- **`Missao.recongelarRecompensa`** escreve `xp_recompensa`, `tokens_recompensa`, `complexidade`,
  `versao_formula`, `multiplicador_risco` e `fonte_pote`, e **lança fora de RASCUNHO** — erro de
  programação, não entrada de usuário, porque o serviço já recusou antes.
- **`AtualizarMissaoRequest` ganha `recompensaEmToken` e `complexidade`.** A segunda entrou junto por
  necessidade: sem ela um rascunho TRIBO ou AJUDA não teria COMO mudar a própria recompensa, já que a
  complexidade é o único insumo que essas categorias têm. Peso e volume já eram editáveis, e a
  assimetria não tinha razão — só não aparecia porque nada no app chegava a editar.
- **Fora de RASCUNHO os dois campos são 409**, não 422: a frase é literalmente "não cabe neste
  estado, caberia em outro".
- **`complexidade` declarada numa missão que tem peso E volume é 422.** Mesma regra (3) da criação,
  que lá é 400 por ser verificável só com o corpo; aqui depende da missão.

### A guarda do pote é o ponto delicado

Reduzir a recompensa abaixo do que já foi financiado deixaria a diferença **presa**: a conclusão
debita exatamente `tokensRecompensa`, `CONCLUIDA` é terminal, e o estorno só roda em `CANCELADA` e
`EXPIRADA`. O resto nunca voltaria a ninguém — e a reconciliação seguiria respondendo `integro=true`,
porque ledger e projeção continuam batendo. É precisamente a perda que o ADR 0032 existe para caçar,
e aqui ela é barata de evitar na entrada.

`MissaoService.recongelarRecompensaDoRascunho` recusa com `PoteInsuficienteException` quando a nova
recompensa ficaria abaixo do pote. A saída oferecida ao usuário é cancelar a missão, que estorna aos
financiadores.

---

## O que este ADR deliberadamente NÃO faz

**`PATCH` em ABERTA continua alterando peso, volume e coordenadas sem recalcular a recompensa.**

Isso é explorável: publicar uma ENTREGA com destino distante (recompensa alta) e depois aproximar o
destino, mantendo o valor. **Não é criado por esta mudança** — existe na API desde que o PATCH
existe, e a UI nova nem oferece o caminho, porque em ABERTA a tela não envia os campos de recompensa.
O que muda é que a edição deixa de ser invisível e fica fácil de descobrir.

Fechá-lo é outra decisão, e ela não é óbvia: recalcular em ABERTA mexe no contrato de quem está
prestes a aceitar, que é exatamente o que o javadoc original protege. As saídas plausíveis são
recusar a edição de insumo depois de publicada, ou recalcular e avisar. **Fica registrado aqui como
trabalho seguinte, não como esquecimento** — é a mesma disciplina do §8 do ADR 0024.

---

## Consequências

**Positivas:**

- **Quem está preso se destrava sem perder o que escreveu.** É o fluxo que o problema relatado pedia,
  e `patchParaSoXpDestravaAPublicacao` o exercita inteiro: cria com token, leva 422, edita, publica.
- A missão em rascunho deixa de poder ter insumos que não explicam a própria recompensa.
- `versao_formula` passa a dizer a verdade também depois de uma edição: o rascunho é explicado pela
  calibração vigente quando foi editado, e não por uma que não o produziu.

**Negativas / trade-offs:**

- **Editar um rascunho pode MUDAR o valor dele, e o usuário precisa entender isso.** A tela diz
  ("enquanto é rascunho, a recompensa acompanha o que você mudar aqui") e a prévia mostra o valor
  novo enquanto se digita, mas é uma surpresa possível para quem só queria corrigir um título.
- **Um rascunho já financiado fica mais rígido do que era.** Baixar a recompensa dele passa a exigir
  cancelar e recriar. É o preço de não deixar token preso, e o cancelamento estorna.
- A regra agora depende do STATUS em três lugares (o serviço, o DTO do app e a tela), e os três
  precisam concordar sobre o que vale em RASCUNHO. O teste que prende isso é
  `patchDeRecompensaEmMissaoAbertaEh409`, mais o caso de app que verifica que a tela não envia os
  campos fora de rascunho.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Não recalcular nada, e só permitir trocar `recompensaEmToken` | Metade da regra: peso e volume continuariam editáveis sem efeito no valor, e o rascunho seguiria podendo ter insumos que não o explicam. A edição de insumo é o caso comum; a troca de forma é o raro. |
| Recalcular também em ABERTA | Muda o contrato de quem está prestes a aceitar — exatamente o que o javadoc de `editarRascunho` protege, e o argumento continua válido ali. Fechar o buraco de ABERTA é decisão própria, e embuti-la aqui faria duas decisões viajarem sob um ADR só (o ADR 0032 registra essa recusa como precedente). |
| Estornar o pote automaticamente ao baixar a recompensa | Um `PATCH` que movimenta o ledger é uma operação de valor disfarçada de edição, e passaria a exigir `Idempotency-Key`, ordem de lock e trilha própria. Recusar com uma mensagem que aponta o cancelamento custa uma linha e não esconde dinheiro dentro de um verbo que não o anuncia. |
| Deixar `complexidade` fora do PATCH | Faria a tela de edição renderizar chips de complexidade inertes para TRIBO e AJUDA — as duas categorias em que ela é o ÚNICO insumo. Um controle que parece funcionar e não faz nada é pior que a assimetria que ele esconderia. |
| Recusar edição de rascunho financiado por completo | Mais simples, e mais rígido do que precisa: mudar o título ou o bairro de um rascunho financiado não ameaça pote nenhum. A guarda certa é sobre o VALOR resultante, não sobre a existência de pote. |

---

## Revisão (2026-09-22, antes do merge)

A regra acima — "RASCUNHO recalcula, ABERTA não" — **foi substituída no mesmo dia, antes de a branch
ser merjada.** O texto original fica onde está: ele registra um raciocínio que parecia certo e
errou por uma razão específica, e é essa razão que vale guardar.

### O que estava errado

O ADR acima traçou a linha na PUBLICAÇÃO, com o argumento do javadoc de `editarRascunho`: depois de
ABERTA a recompensa é promessa feita a quem está prestes a aceitar. O argumento é bom — e não é o
que efetivamente protege a recompensa de uma missão publicada. **Quem a protege é o POTE.**

A prova é a missão que não tem pote nenhum. Uma ENTREGA criada por humano é `FontePote.CUNHAGEM`:
publica sem pote, e o token é EMITIDO na conclusão. Nela, o congelamento não protegia ninguém e
escondia o oposto — publicar com destino a 8 km, aproximá-lo para 500 m e manter a recompensa alta
é emissão de token sem contrapartida. É o defeito estrutural que o ADR 0024 fechou por um caminho,
sobrevivendo por outro. A seção "Fica FORA" acima nomeava isso como trabalho seguinte; ele virou
trabalho desta entrega.

### A regra que vale

**A recompensa acompanha os dados em TODO estado editável.** O que a publicação muda não é SE ela
recalcula — é o que acontece quando o pote não pode acompanhar.

| Fonte, com a missão em ABERTA | Pote comprometido? | Recalcular |
|---|---|---|
| `CUNHAGEM` (ENTREGA de humano) | não — cunha na conclusão | livre |
| `SEM_TOKEN` | não — o token é 0 | livre (só o XP muda) |
| `COMUNIDADE` publicada | sim, e `pote == recompensa` exatamente | recalcula, e **422 se o valor mudar** |
| `PATROCINADOR` | sim | inalcançável: o criador é o usuário-sistema, e `validarEdicao` exige `ator == criador` |

A guarda é **bidirecional**, e as duas metades protegem invariantes **diferentes**:

- **para baixo** — a conclusão debita exatamente `tokensRecompensa` e CONCLUIDA é terminal; a sobra
  do pote nunca volta a ninguém, e a reconciliação segue respondendo `integro=true`. É a perda que o
  ADR 0032 existe para caçar. Vale em qualquer estado;
- **para cima** — o pote deixa de cobrir a recompensa e a conclusão passa a falhar com 422 **para
  sempre**: exatamente a classe de missão impossível de concluir que
  `validarPoteSuficienteParaPublicar` impede. Só vale depois de publicada, porque em RASCUNHO subir
  acima do pote é normal — quem financia ainda pode completar.

O efeito prático em `COMUNIDADE` publicada é que só sobrevive a edição de valor NEUTRO. Quem precisa
mudar o valor cancela a missão, que estorna aos financiadores, e cria outra.

### A condição que não é otimização

**O recálculo só roda quando um INSUMO vem no corpo** (`mexeuEmInsumoDaRecompensa`: peso, volume,
complexidade, coordenadas, `recompensaEmToken`). Sem essa condição, corrigir a vírgula do título de
uma missão publicada e financiada passaria a falhar com 422 sempre que a calibração do YAML tivesse
mudado desde a criação — o valor novo divergiria de um pote já fechado, por um motivo que não tem
nada a ver com a edição. A recompensa acompanha os DADOS; título, descrição, endereço textual,
janela e raio de check-in não são dados dela.

**Cuidado ao acrescentar campo ao DTO de edição:** um insumo novo que não entre naquela lista
reabre o defeito original em silêncio.

### O que continua fora

`recompensaEmToken` permanece RASCUNHO-only, com 409 fora dele. Ele não é insumo — é a FONTE, e ela
congela na publicação. A linha ficou sendo *"insumo segue os dados em todo estado editável; a fonte
congela ao publicar"*, que é mais estreita e mais defensável que a anterior.

Continua fora, também, notificar quem viu a missão no radar com o valor antigo: o cache de
proximidade já é invalidado após o commit, e avisar pessoa por pessoa é fan-out, que é outra decisão.

### Por que revisão no lugar, e não um ADR novo

A branch não foi merjada e a regra original nunca chegou a valer para ninguém. Um ADR 0037 para
retificar um ADR 0036 do mesmo dia produziria dois documentos sobre uma decisão só, e o índice de
ADRs — que é `ls docs/adr/` — passaria a mentir sobre quantas decisões existem. O precedente do §8
do ADR 0024 (texto errado preservado, marcado no lugar) é o que está sendo seguido aqui.
