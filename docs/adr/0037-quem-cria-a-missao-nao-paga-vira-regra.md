# 0037 — "Quem cria a missão não paga" vira regra imposta, depois de dois anos sendo só premissa

**Data:** 2026-09-22
**Status:** Aceito
**Torna verdadeira:** a alternativa descartada do [0025](./0025-ajuda-paga-do-pote.md), que descrevia
como recusado um comportamento que o código sempre permitiu

---

## Contexto

"Quem cria a missão NÃO paga" é a premissa central da economia do Omni-Tribo. Ela abre a seção
Economia do `CLAUDE.md`, é a tese do [ADR 0009](./0009-economia-do-cuidado-token-como-recompensa.md),
é citada no [0024](./0024-carteira-de-patrocinador.md), no [0025](./0025-ajuda-paga-do-pote.md) e no
[0035](./0035-missao-comunitaria-sem-recompensa-em-token.md), e aparece no javadoc de
`FontePote.COMUNIDADE` — *"em nenhuma delas quem financia é o criador"*.

**Nenhuma linha de código a impunha.**

`FinanciamentoService.validarAutorizacao` fazia duas checagens, e as duas são sobre TRIBO:

```java
// o financiador pertence à tribo declarada no path?
if (triboFinanciador.isEmpty() || !triboFinanciador.get().equals(triboId)) { … }
// o financiador está na mesma tribo do criador?
if (!consultaAfiliacao.mesmaTribo(financiadorId, missao.getCriadorId())) { … }
```

O criador passa nas duas, trivialmente: ele está na própria tribo. `POST /tribos/{id}/financiamentos`
aceitava o criador financiando a própria missão desde que o endpoint existe.

**Três coisas tornam isso pior do que uma lacuna comum:**

1. **O ADR 0025 descreve o comportamento como recusado.** A tabela de alternativas descartadas dele
   traz, textualmente: *"Deixar o criador financiar a própria AJUDA | Violaria o ADR 0009 […], e
   reproduziria exatamente o cenário que o §8 do 0024 temia."* Estava escrito como decisão tomada. Só
   que ninguém a implementou, e nenhum teste a cobrava.
2. **`ConservacaoTokensTest` dependia da brecha.** O teste que prova a conservação da moeda em todas
   as categorias tinha dois usuários, `criador` e `executor`, e era o **criador** que financiava o
   pote. O teste mais econômico do repositório demonstrava, a cada execução, o que o produto diz que
   não pode acontecer.
3. **O ADR 0009 nasceu de um defeito MEDIDO da mesma família.** R$ 1.500 criados do nada porque o
   criador nunca era debitado — *"o saldo do criador não se moveu em nenhum momento: ele não pagou,
   porque o produto nunca previu que pagasse"*. A premissa existe porque a ausência dela já custou
   caro uma vez.

O achado veio de lado: ao planejar a correção de outro pendente, a pergunta era se a `alice` poderia
financiar a própria missão no teste e2e para recuperar cobertura. A resposta foi **sim, o código
deixa** — e a resposta deveria ter sido não.

---

## Decisão

**`validarAutorizacao` recusa `financiadorId == missao.criadorId`, com 422.**

```
"Quem cria a missão não paga por ela. O pote é formado por outros membros da tribo."
```

Fica **onde `validarAutorizacao` já roda: antes da sondagem de idempotência.** Isso é seguro aqui, e
a razão é específica — não há replay legítimo a proteger. A operação nunca deveria ter sido possível,
então não existe chave de idempotência gravada de um autofinanciamento anterior que mereça resposta
de sucesso. A ordem `autorização → sondagem → estado` do check-in continua valendo para tudo o mais.

É `RegraNegocioVioladaException` (422) e não `AcessoNegadoException` (403) porque a pessoa TEM acesso
ao recurso: ela é da tribo, a missão é dela, e o pedido está bem formado. O que não cabe é a
operação, dado quem a pede — que é a definição de 422 neste repositório.

---

## O que muda nos testes, e por quê isso importa

- **`ConservacaoTokensTest` ganha um terceiro usuário.** Antes: `criador` (com saldo) e `executor`
  (zerado). Agora: `criador`, `financiador` (com saldo) e `executor`. **O criador continua com
  saldo**, de propósito: assim um débito indevido nele fica VISÍVEL caso a regra caia, em vez de
  passar despercebido por falta de fundos.
- **`FinanciamentoControllerTest.estornoComDoisFinanciadoresDevolveAParteDeCadaUm` usava o criador
  como segunda ponta.** Passou a usar um segundo membro, e a assertion sobre o saldo do criador —
  que já existia — mudou de sentido: era "ele recebeu a parte dele de volta", virou "ele não pagou
  nada em momento nenhum".
- **`tools/evidencias/conservacao-por-categoria.sh` não precisou mudar** — verificado antes: ele já
  passa criador e financiador distintos (`BOB`/`CAROL`).

A troca em `ConservacaoTokensTest` é a parte que merece atenção numa banca. Um teste que depende de
um comportamento proibido não é um teste a menos: é um teste que **atesta** o comportamento errado.

---

## Consequências

**Positivas:**

- A premissa mais citada da economia passa a ser verificável por execução, e não por leitura de ADR.
- O ADR 0025 deixa de conter uma afirmação falsa sobre o próprio sistema.
- O `detail` da recusa **explica o modelo** em vez de só negar: "o pote é formado por outros membros
  da tribo" é a frase que ensina por que a missão precisa de alguém além de quem a criou.

**Negativas / trade-offs:**

- **Co-financiar a própria missão era defensável em abstrato, e deixa de ser possível.** O criador
  também é membro da tribo, e "eu entro com 10 e a tribo completa" não é obviamente abusivo. A recusa
  vale a pena mesmo assim: a premissa que o produto defende oralmente numa banca não pode ter uma
  exceção que ninguém consegue explicar em uma frase, e qualquer fração aberta reintroduz por outro
  caminho a pergunta "quem paga por isso?" que o ADR 0009 existe para responder.
- **Missão comunitária de um criador sem vizinhos disponíveis fica sem saída em token.** É atenuado
  pelo ADR 0035: ela pode nascer valendo só XP e publicar na hora.
- Uma tribo com **um membro só** não consegue financiar nada. É consequência direta e correta do
  modelo — pote comunitário pressupõe comunidade.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Deixar permitido e corrigir a documentação | Era a saída barata: a premissa vira "quem cria não é OBRIGADO a pagar". Mas o ADR 0009 nasceu de um defeito medido em que o criador não pagava, e a economia inteira é apresentada sobre essa frase. Enfraquecê-la para caber no código é ajustar a tese ao acidente. |
| Permitir até um teto (ex.: 50% do pote) | Um limiar que ninguém consegue justificar numa frase. Por que 50 e não 30? E a premissa passaria a ter uma exceção com número dentro, que é a forma mais frágil de regra: a primeira recalibração a transforma noutra coisa. |
| Barrar no app, não no servidor | O app nem oferece financiamento hoje — a tela não existe. A regra estaria protegendo contra ninguém, e `curl` continuaria passando. Validação de valor é sempre no servidor (Regras não negociáveis). |
| CHECK no banco ligando `lancamento` ao criador da missão | `carteira` referencia `missao_id` como UUID puro, sem FK, deliberadamente (ADR 0008). Um CHECK ali exigiria a FK que a fronteira entre módulos recusa, e ainda assim não impediria o débito — só o registraria como inválido depois de feito. |
| Recusar com 403 | A pessoa tem acesso ao recurso: é da tribo dela e a missão é dela. O que não cabe é a operação, e 422 é o código que este repositório usa para "cabe no estado, mas os dados não satisfazem a regra". |
