# 0035 — Missão comunitária sem recompensa em token: a categoria que a tese tinha e a economia não escrevia

**Data:** 2026-09-22
**Status:** Aceito
**Completa:** [0025](./0025-ajuda-paga-do-pote.md) — resolve o trade-off que aquele ADR registrou e deixou em aberto

---

## Contexto

O ADR 0025 trouxe AJUDA para `FontePote.COMUNIDADE`, e com isso publicar uma AJUDA passou a exigir
pote financiado por outro membro da tribo. A decisão está certa e não muda. O que ela produziu está
escrito nas **Negativas** do próprio ADR 0025, palavra por palavra:

> **AJUDA deixa de ir ao ar na hora.** Um vizinho que peça ajuda fica em RASCUNHO até alguém
> financiar o pote. […] É a mudança de comportamento mais visível para o usuário final, e o app
> precisa explicá-la.
>
> […] num mutirão, quem financia também se beneficia (é bem coletivo); numa AJUDA o beneficiário é
> uma pessoa só. **Se financiar favor alheio se mostrar pouco atraente na prática, AJUDA fica
> represada em RASCUNHO.** Não é problema técnico e não muda o desenho — é hipótese de adoção, e o
> dado para testá-la não existe (não há operação).

**O dado apareceu.** Ao usar o app, criar uma missão TRIBO ou AJUDA e não conseguir publicá-la foi
descrito como "um impeditivo gigante". A hipótese de adoção foi testada pelo uso e falhou.

Duas evidências independentes confirmam que não era percepção isolada:

1. **`src/api/__tests__/ciclo.e2e.test.ts` estava VERMELHO desde 2026-08-21** e ninguém notou. Ele
   cria uma AJUDA e publica no passo 3; desde o ADR 0025 aquele passo é 422. Passou despercebido
   porque `test:e2e` fica fora do CI de propósito (exige backend de pé). O teste que exercita o
   ciclo inteiro do produto quebrou no dia da mudança, e a quebra ficou invisível por um mês.
2. **Nenhuma AJUDA financiada existe em lugar nenhum**, nem no seed. O caminho existia só no teste
   que o ADR 0025 escreveu para ele.

O erro a evitar aqui é o óbvio: afrouxar a conservação para destravar a publicação. Foi a tentação
que o ADR 0024 fechou e o ADR 0025 apertou, e ceder a ela reabriria a cunhagem implícita que o
`EVOLUCAO-ARQUITETURAL.md` registra como o defeito estrutural do projeto.

**A saída é outra, e ela não é um relaxamento — é uma ausência que faltava ser nomeada.** O produto
sempre teve, na tese, a missão que vale REPUTAÇÃO e não moeda: o mutirão de vizinhos, o favor de
quem carrega um móvel. A economia nunca escreveu essa categoria. Toda missão, sem exceção, tinha de
pagar token — e a calculadora sequer sabia devolver zero, porque aplica `Math.max(tokens, 1L)`.

---

## Decisão

**Adotamos `FontePote.SEM_TOKEN`: TRIBO e AJUDA podem ser criadas recompensando só em XP.** Elas
nascem com `tokens_recompensa = 0` e `pote_tokens = 0`, publicam sem financiamento nenhum, e a
conclusão paga XP e nada mais.

Cinco peças:

1. **`CriarMissaoRequest.recompensaEmToken`** (`Boolean`, nulo = `true`). Nulo equivale a `true`
   para que nenhum cliente já integrado mude de comportamento. `false` em ENTREGA ou COLETA é
   **400 apontando o campo** — as duas movem objeto físico e têm custo real de execução.
2. **`Recompensa.semToken()`**, aplicada em `MissaoService.calcularRecompensa` — o ponto que
   `POST /missoes` e `POST /missoes/previa-recompensa` compartilham, então a prévia nunca promete
   token que a criação não paga. **Zera DEPOIS de calcular**: o XP é derivado dos tokens
   (`xp = tokens * xpPorToken`), e zerar na entrada zeraria os dois.
3. **`FontePote.SEM_TOKEN`** (V30), derivada da RECOMPENSA e não de um parâmetro novo do construtor
   de `Missao`, que já tem 24 posições. Como a calculadora tem piso de 1 token, `tokens == 0` é
   exatamente "o criador escolheu só XP", e a equivalência `SEM_TOKEN ⇔ tokens_recompensa = 0` vale
   nos dois sentidos, por construção.
4. **A conclusão não passa pelo ledger** quando não há o que mover, e a sondagem de replay dela
   passa a ser por **ESTADO**. Ver §"A consequência que quase passou batido".
5. **`FinanciamentoService.validarEstado` recusa `SEM_TOKEN`**, com mensagem que diz o que fazer.

**O padrão por categoria — AJUDA nasce só-XP, TRIBO nasce com token — mora no APP, não no
servidor.** É decisão de produto, e o argumento é o do próprio ADR 0025 lido ao contrário: no
mutirão quem financia também se beneficia, então pedir o pote à tribo é coerente; numa AJUDA o
beneficiário é uma pessoa só. Servidor com padrão por categoria seria política escondida — quem lê o
corpo da requisição não conseguiria dizer quanto a missão vale.

---

## SEM_TOKEN não é uma quarta ponta da invariante

A tabela das três pontas do `CLAUDE.md` **não muda**, e é importante entender por quê.

| Ponta | Efeito |
|---|---|
| `APORTE_PATROCINADOR` | sobe (emite) |
| conclusão de ENTREGA com `fonte_pote = CUNHAGEM` | sobe (emite) |
| `RESGATE` | desce (queima) |

Uma missão `SEM_TOKEN` não emite nem queima: ela **não participa** da invariante. Isso é diferente
de "muda a soma em zero", e a distinção não é retórica — é o que a assertion do teste mede.
`ConservacaoTokensTest.missaoSemTokenNaoMoveNemRegistraNada` verifica **Δ = 0 E nenhum lançamento**,
porque só o Δ passaria igual se a missão cunhasse e queimasse o mesmo valor.

---

## A consequência que quase passou batido

**O ledger proíbe lançamento zerado.** O compact constructor de `Movimento` recusa mover 0 BRL e 0
token — "Movimento precisa mover BRL ou tokens." —, espelhando `ck_lancamento_valor_nao_nulo`. O
javadoc explica: um lançamento de zero consumiria uma chave de idempotência sem mover nada, e o
cliente leria isso como sucesso. Essa barreira está certa e **não foi tocada**.

Logo a conclusão de uma missão só-XP não pode chamar `creditarConclusao`. E aí vem o efeito de
segunda ordem: **a sondagem de replay da conclusão é feita PELO LANÇAMENTO**
(`MissaoService.concluirComCredito`, o bloco que sonda `ChaveIdempotencia.conclusaoMissao` antes de
validar a transição). Sem lançamento, ela é cega — e um retry de `POST /confirmar` numa missão já
CONCLUIDA cairia em **409**, dizendo "esta operação não é permitida no estado atual" a quem apenas
perdeu a resposta na rede.

Para essa missão a idempotência passa a ser **por ESTADO da linha**, sob o mesmo `FOR UPDATE`. O
precedente é o ADR 0031, que estabeleceu "ação idempotente por ESTADO, sem `Idempotency-Key`" para o
reenfileiramento da outbox. `CONCLUIDA` é terminal, então não há ambiguidade sobre qual conclusão
foi aquela.

Dois testes travam isso, e sem eles a regressão é invisível:
`FinanciamentoControllerTest.confirmarDuasVezesMissaoSemTokenEhIdempotente` e o passo 10 do ciclo
e2e.

---

## Consequências

**Positivas:**

- **O atrito que motivou este ADR desaparece no caminho mais usado.** Uma AJUDA vai ao ar no
  toque, como antes do ADR 0025 — mas sem cunhar nada, que era o preço que se pagava por isso.
- **`MissaoResponse` passa a expor `fontePote`.** Ele existia na entidade desde a V23 e não saía por
  via nenhuma, apesar de uma das três razões do javadoc de `FontePote` ser "o app consegue explicar
  de onde vem a recompensa". Sem ele, `poteTokens: 0` é ambíguo entre "falta financiar", "o
  patrocinador paga" e "esta missão não paga token" — três telas diferentes.
- **`TipoProblema.POTE_INSUFICIENTE`** dá ao 422 de publicação a reação de UI que ele pedia (ADR
  0010): a tela não manda corrigir o pedido e tentar de novo, ela oferece financiar ou editar.
- O ciclo e2e do mobile **voltou a ser verde**, e agora exercita a conclusão que paga XP sem
  escrever no ledger — um caminho que nenhum teste cobria.

**Negativas / trade-offs:**

- **O financiamento comunitário deixa de ser o caminho padrão de AJUDA.** Ele continua existindo,
  visível num chip, mas quem não o procurar não o encontra. É uma troca consciente: a moeda
  comunitária perde exercício no uso comum, e ganha um produto que não trava. O pitch precisa mostrar
  o financiamento de propósito, em TRIBO, em vez de esperar que ele apareça sozinho.
- **Uma quarta constante no enum é uma quarta constante em todo `switch` sobre ele.** Hoje são
  três lugares (`pagaTokensDoPote`, `validarEstado`, a derivação em `Missao`), e o compilador não
  cobra exaustividade em `if` encadeado.
- **Missão só-XP não aceita financiamento, e a recusa é um 422 a mais para o app conhecer.** A
  alternativa — deixar o pote crescer numa missão de recompensa zero — deixaria o token do
  financiador preso numa missão que nunca o devolve, com a reconciliação respondendo `integro=true`.
- **O ciclo e2e do mobile deixou de exercitar o crédito em TOKEN na conclusão.** Financiar exigiria
  um terceiro login (só o `admin` está na tribo de alice) e o teto de 5 tentativas por minuto não
  comporta: este arquivo faz dois logins e `integracao.e2e.test.ts` faz outros dois. Quem cobre esse
  caminho ponta a ponta é `FinanciamentoControllerTest.cicloCompleto_financiarPublicarConcluir_conservaOsTokens`,
  no backend, e o javadoc do teste e2e diz isso em voz alta.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Deixar o criador financiar a própria missão | Viola o ADR 0009 ("quem cria a missão NÃO paga"), que é a premissa do produto. É a mesma alternativa que o ADR 0025 já recusou. |
| Publicar com pote PARCIAL e pagar o que houver na conclusão | Quebra a recompensa congelada: o executor aceitaria por um valor e receberia outro, e descobriria no fim. A promessa ao executor é a única coisa que a máquina de estados protege de ponta a ponta. |
| Voltar AJUDA para `CUNHAGEM` | É desfazer o ADR 0025 e reabrir a cunhagem implícita por missão — o defeito que o `EVOLUCAO-ARQUITETURAL.md` registra, invisível para a reconciliação. Destravaria a publicação criando token do nada. |
| Relaxar `Movimento` para aceitar lançamento de zero | Destruiria uma barreira boa (`ck_lancamento_valor_nao_nulo`) por causa de um caso. Um lançamento zerado consome chave de idempotência sem mover nada, e o cliente lê como sucesso. A saída certa era não escrever lançamento nenhum. |
| Reusar `COMUNIDADE` com `tokens_recompensa = 0` | Não custaria migration, e é a alternativa mais tentadora. Mas `COMUNIDADE` AFIRMA "pote financiado por membros da tribo", e nessas missões esse pote nunca existe: `validarEstado` deixaria o financiamento passar e a recusa sairia de `validarTeto` como aritmética sem sentido ("pote ficaria com 10, acima da recompensa de 0. Faltam apenas 0."). É o argumento do §3 do ADR 0024 outra vez — regra que depende da fonte se lê da FONTE. |
| Um 25º parâmetro booleano no construtor de `Missao` | O comentário da própria classe avisa que ali `xpRecompensa` e `raioCheckinM` já são intercambiáveis para o compilador. Derivar de `recompensa.tokens() == 0` não acrescenta posição nenhuma e faz a equivalência valer nos dois sentidos. |
| Padrão por categoria decidido no SERVIDOR | Seria política escondida: quem lê o corpo da requisição não conseguiria dizer quanto a missão vale, e mudar o padrão viraria mudança de contrato silenciosa. O padrão é de produto e mora no app; o servidor só recebe a escolha explícita. |
| CHECK no banco ligando `fonte_pote = 'SEM_TOKEN'` a `tokens_recompensa = 0` | Mesma razão da V23 para não ter CHECK de coerência com a categoria: ele reprovaria os INSERTs dos seeds 900+, que rodam depois da migration. A coerência mora no construtor, que é ponto único, e `MigracaoTest` prova que o CHECK do enum continua fechado. |
