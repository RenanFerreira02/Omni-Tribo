# 0024 — Carteira de patrocinador: a cunhagem sai do fim do ciclo e vira um evento explícito

**Data:** 2026-08-20
**Status:** Aceito

---

## Contexto

Desde o ADR 0009 o projeto afirma que a oferta de TOKEN é conservada: financiar uma missão move
token da carteira do membro para `missao.pote_tokens`, concluir move do pote para o executor, e a
soma `SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)` não muda. A afirmação sempre teve uma
nota de rodapé — a **Pendência #1** —, e ela era maior do que parecia.

`MissaoService.pagaTokensDoPote` decidia por CATEGORIA: TRIBO e COLETA pagavam do pote, ENTREGA e
AJUDA **cunhavam** os tokens na conclusão. Ou seja, a conservação valia para duas categorias, não
para o sistema. Três agravantes:

- **A cunhagem era invisível para o instrumento que existe para achá-la.** Reconciliação compara o
  ledger com a projeção de saldo, e as duas ficam consistentes quando se cria token do nada — o
  crédito tem lançamento e o lançamento bate com o saldo. `GET /admin/carteiras/reconciliacao`
  respondia `integro=true` o tempo todo. A invariante violada era a CONSERVAÇÃO, que é outra coisa.
- **O webhook de entrega falida ampliou a exposição.** Cada entrega convertida cunhava, e a F12c
  acrescentou o multiplicador de risco, que cunha até 1,5× — foi por isso que o teto ficou estreito.
- **Não havia registro de que uma emissão tinha acontecido.** Nenhum ator, nenhuma trilha, nenhum
  número que alguém pudesse somar depois.

Contornar exigindo pote da comunidade para ENTREGA não era opção: faria membros da tribo custearem a
logística do varejista, que é o inverso do modelo. O financiador correto sempre foi o PATROCINADOR —
entrega que falhou custa re-entrega, armazenagem e risco de perder o cliente, então patrocinar o pote
sai mais barato que o fracasso. Faltava a carteira dele existir.

---

## Decisão

**Adotamos o patrocinador como um PAPEL de `usuario`, e movemos a cunhagem para um endpoint ADMIN
explícito.**

A cunhagem **não foi eliminada** — foi deslocada, e essa é a tese desta decisão. Antes ela estava no
FIM do ciclo, implícita e por missão. Agora está no COMEÇO, num evento único chamado
`APORTE_PATROCINADOR`, com ator identificado, trilha de auditoria e chave de idempotência. O que se
ganha é que a emissão passa a ser **contável**: a afirmação forte vale a partir daqui.

> `SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)` é constante em TODO o ciclo de missões, nas
> quatro categorias. Só um aporte a altera.

Cinco peças:

1. **`papel = 'PATROCINADOR'`** em `usuario` (V23). A conta é `status = 'INATIVO'` e nunca autentica
   — mesmo molde do usuário-sistema da V21. Não confere autorização nenhuma: quem opera em nome do
   patrocinador é um ADMIN.
2. **Tabela `patrocinador`** ligando `transportadora_slug` ao titular. Preenche uma lacuna real: até
   a V23 nada no sistema ligava o slug do cabeçalho `X-Transportadora` a um titular de carteira.
3. **`missao.fonte_pote`** — `COMUNIDADE`, `PATROCINADOR` ou `CUNHAGEM` —, congelada na criação.
   Substitui a decisão por categoria.
4. **Financiamento na conversão**, dentro da transação do webhook, ANTES de a missão ser gravada.
5. **`POST /api/v1/admin/patrocinadores` e `/{id}/aportes`**, só ADMIN, o aporte com
   `Idempotency-Key` obrigatório.

---

## Por que o aporte é em TOKEN, e não uma conversão de reais (§2b)

O patrocinador **aporta em TOKEN**. `entrega_falida.valor_ofertado_brl` continua sendo gravado —
é o valor que a transportadora declara que a entrega vale para ela —, mas **nunca é convertido em
token**. Ele é registro de negócio, insumo da fórmula de recompensa (calibração
`tokens-por-real-ofertado`) e alimento do painel de impacto da F20. Nada mais.

A alternativa óbvia era converter: a transportadora informa R$ 18,00, o sistema credita N tokens a
uma taxa. **É exatamente isso que não pode existir.** Uma taxa BRL→token, aplicada de forma
sistemática, **é uma cotação** — e uma cotação com dois lados, porque quem sabe quantos tokens vale
um real sabe quantos reais vale um token. O ADR 0009 §6 recusa a cotação token→real por uma razão
que não é estética: **token conversível é dinheiro**, e dinheiro traz KYC, prevenção a lavagem e
enquadramento regulatório junto. O projeto declarou isso fora de escopo e a decisão continua valendo.

A relação permanece **unidirecional**, como o CLAUDE.md a descreve: o valor ofertado ENTRA na fórmula
como insumo de calibração, para ordenar missões por urgência; nenhum ator compra token com dinheiro,
e token não é resgatável em reais. Aportar em token mantém essa assimetria — o patrocinador decide
quantos tokens põe na carteira dele, e essa decisão é comercial, não uma taxa de câmbio que o sistema
publica e aplica.

Consequência aceita: **o aporte é um número que alguém escolhe**, não algo derivado do valor da
encomenda. É menos automático e mais honesto — e é o motivo de o endpoint de aporte ser ADMIN,
auditado e idempotente, em vez de um efeito colateral do webhook.

---

## Por que `fonte_pote` e não a categoria (§3)

Virar `pagaTokensDoPote` para incluir ENTREGA teria quebrado a **ENTREGA criada por humano**: ela
ficaria impublicável, porque `FinanciamentoService.validarEstado` recusa financiamento de ENTREGA e
o pote nunca alcançaria a recompensa exigida por `validarPoteSuficienteParaPublicar`. As duas
ENTREGAs — a do webhook, patrocinada, e a de humano, sem financiador — precisam ser distinguíveis, e
categoria não as distingue.

Coluna por missão pelas mesmas três razões que a V21 deu para `nivel_minimo`: o app consegue explicar
de onde vem a recompensa, a regra fica auditável junto com a missão que a aplicou, e recalibrar
depois não reescreve o passado.

---

## Por que um papel, e não titular polimórfico na carteira (§4)

A alternativa avaliada era `carteira (titular_tipo, titular_id)`, com `patrocinador` fora de
`usuario`. Foi descartada por três motivos, em ordem de peso:

1. **Custaria a FK.** Hoje `carteira.usuario_id REFERENCES usuario(id)` garante NO BANCO que toda
   carteira tem titular existente. Com titular polimórfico essa garantia vira responsabilidade de
   código, e o banco deixa de conseguir provar o que hoje prova — contrariando a doutrina de
   "barreira no banco atrás da regra no serviço" que a V13 aplicou à própria carteira.
2. **O precedente já estava pago.** `UsuarioSistema` (V21) estabeleceu que uma linha em `usuario`
   com `status='INATIVO'` é como este projeto dá identidade a um ator não-humano que possui
   registros. O patrocinador é o segundo caso da mesma forma, não uma forma nova.
3. **Nenhum javadoc de concorrência precisou ser reescrito.** `buscarIdPorUsuario` vs
   `findByUsuarioId`, a ordem global de lock, o contrato `MANDATORY` do `LivroRazaoService` — tudo
   continua literalmente verdadeiro, porque o patrocinador entra pela mesma porta que um usuário. A
   alternativa mexeria na assinatura de `ProvisionamentoCarteira` e em duas queries nativas, exigindo
   reverificar cada invariante por leitura, sem teste que acusasse a perda de um deles.

O custo assumido: o patrocinador aparece em `SELECT * FROM usuario` e precisa ser filtrado em
listagens e na exportação LGPD — o mesmo custo que `UsuarioSistema` já impõe.

---

## Por que o patrocinador não passa pelo endpoint de financiamento (§5)

`FinanciamentoService.validarAutorizacao` exige que o financiador pertença à tribo do path E à mesma
tribo do criador. Um patrocinador reprova duas vezes: não tem tribo, e o criador da missão de
retirada é o usuário-sistema, que também não tem. E `validarEstado` recusa ENTREGA.

A porta `carteira/api/FinanciamentoMissao` **não assume tribo nenhuma** — ela resolve o financiador
por `buscarIdPorUsuario` —, então o caminho novo (`debitarPatrocinador`) reusa toda a mecânica de
lock e ledger sem herdar as regras de pertencimento, que são de `missoes`.

---

## Saldo insuficiente é DESFECHO, não erro (§6)

`debitarPatrocinador` devolve `Optional.empty()` em vez de lançar, e o webhook responde **200 com
`SEM_PATROCINIO`**. É a doutrina do ADR 0021 aplicada de novo: a encomenda já está fisicamente na
loja, a recusa precisa ser GRAVADA, e lançar de dentro da transação apagaria a linha no rollback.
Devolver 4xx faria a transportadora reenviar em laço contra uma condição que o reenvio não muda.

As três causas — patrocinador inexistente, desativado, sem saldo — colapsam num valor só. Distingui-
las contaria à transportadora o estado financeiro de um terceiro sem nenhum ganho operacional: ela
precisa saber que reenviar não adianta, não por quê. Mesma doutrina do 401 único do
`HmacWebhookFilter`.

`PatrocinadorResponse` **não traz saldo** pela razão simétrica: saldo muda sob lock, e devolvê-lo
numa listagem seria uma leitura que envelhece antes de chegar à tela. O número atual vem na resposta
do aporte, lido sob o lock que acabou de escrevê-lo.

---

## Ordem de lock e throughput (§7)

A ordem global documentada é `missao → carteira (id crescente) → usuario`. A conversão introduz
`ponto_custodia → carteira`, e isso **estende** a ordem sem invertê-la: quando `debitarPatrocinador`
roda, a linha da missão ainda não existe no banco — o UUID dela é gerado pelo chamador —, então não
há missão para disputar.

O custo aceito conscientemente: **todo webhook da mesma transportadora serializa na única linha de
carteira do patrocinador dela.** Não é problema na escala do projeto (um bairro, uma transportadora
por contrato), e a alternativa — sharding de carteira ou saldo otimista — trocaria uma garantia forte
por complexidade que nada aqui justifica.

---

## O que NÃO foi resolvido (§8)

> **RETIFICADO em 2026-08-21 pelo [ADR 0025](./0025-ajuda-paga-do-pote.md).** O parágrafo abaixo
> sobre AJUDA está ERRADO e ficou registrado como estava. Ele conflacionou "quem cria não paga"
> (ADR 0009, que vale) com "a comunidade não deve financiar" (que em TRIBO já era falso: o pote é
> formado por OUTROS membros, não pelo criador). O argumento do varejista é sobre ENTREGA e nunca foi
> sobre AJUDA. Desde o ADR 0025, AJUDA paga do pote como TRIBO.

**AJUDA continua cunhando**, e `FontePote.CUNHAGEM` declara isso na linha da missão em vez de
escondê-lo num `if`. Não há financiador plausível: quem pede ajuda não paga — é a premissa do
produto —, e exigir pote da tribo faria o vizinho custear o favor que ele mesmo pediu.

**ENTREGA criada por humano também continua cunhando**, pelo problema simétrico do varejista.

As duas ficam registradas como trabalho seguinte, não como esquecimento. A diferença em relação ao
estado anterior é que agora elas são **consultáveis** (`SELECT ... WHERE fonte_pote = 'CUNHAGEM'`) e
não uma consequência implícita de um método privado.

---

## Consequências

**Positivas:**

- A conservação passa a valer para o ciclo inteiro, e a emissão passa a ser um número que alguém
  pode somar.
- `ConservacaoTokensTest` deixou de esperar cunhagem em ENTREGA — a mudança de expectativa é a
  evidência de que a lacuna fechou.
- Missão de retirada nunca mais nasce com pote vazio, o que elimina uma classe de missão
  impossível de concluir cuja falha só apareceria no job de expiração.
- O teto do multiplicador de risco (1,5×) **pode** ser reavaliado: ele era estreito porque o risco
  multiplicava emissão sem financiador, e agora multiplica quanto o patrocinador paga do próprio
  saldo. **Ele NÃO muda nesta fase** — reavaliar exige dado que não existe, porque o modelo foi
  treinado em dados sintéticos (ADR 0022). Quem for mexer: o teto vive em DOIS blocos de
  configuração, `app.logistica.risco.multiplicador-maximo` e
  `app.missoes.recompensa.multiplicador-risco-maximo`, e `CoerenciaTetoRiscoTest` trava os dois
  JUNTOS. Alterar um sem o outro deixa o build vermelho de propósito — a duplicação é deliberada
  porque são decisões de donos diferentes (o que o modelo prevê × o quanto a economia se dispõe a
  pagar), e o teste é o preço dela.
- Ganhamos um alerta operacional (`ENTREGA_SEM_PATROCINIO`) que torna visível uma transportadora
  parada por falta de saldo — antes, a encomenda simplesmente não virava missão em silêncio.

**Negativas / trade-offs:**

- O patrocinador aparece como linha de `usuario` e precisa ser filtrado em listagens e na
  exportação LGPD.
- Todo webhook de uma transportadora serializa na carteira dela.
- Uma transportadora sem saldo para de gerar missões, e a encomenda fica no ponto sem ninguém
  acionado. O alerta acima mitiga, mas não resolve sozinho — depende de alguém ler.
- Cadastrar transportadora nova continua exigindo deploy (o segredo HMAC vive em configuração,
  ADR 0021), mas agora exige TAMBÉM um cadastro por endpoint. Os dois podem divergir, e a
  divergência se manifesta como `SEM_PATROCINIO` — indistinguível de saldo zerado do lado de fora.
  O `@Pattern` do slug reduz a chance de divergência por caixa ou espaço, não a elimina.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Titular polimórfico em `carteira` (`titular_tipo`/`titular_id`) | Custaria a FK que hoje prova, no banco, que toda carteira tem titular; e obrigaria a reescrever as duas queries nativas e a assinatura de `ProvisionamentoCarteira`, reverificando por leitura invariantes de concorrência que nenhum teste acusaria se fossem perdidos. Ver §4. |
| `pagaTokensDoPote` por categoria, incluindo ENTREGA | Quebraria a ENTREGA criada por humano, que ficaria impublicável: financiamento de ENTREGA é recusado e o pote nunca alcançaria a recompensa. Ver §3. |
| Reusar `MotivoLancamento.FINANCIAMENTO_TRIBO` no débito do patrocinador | O extrato e a exportação LGPD mostram o motivo cru, e "financiamento de tribo" numa carteira de patrocinador afirmaria um pertencimento que não existe. |
| Reusar `MotivoLancamento.BONUS` para o aporte | `BONUS` é reservado justamente por cunhar SEM sumidouro correspondente. O token aportado tem destino — vai ao pote e do pote ao executor. |
| Saldo insuficiente responder 422 ou 503 | A transportadora reenviaria em laço, e o corpo do erro contaria a ela o estado financeiro do patrocinador. Ver §6. |
| Coluna própria (`sem_patrocinio_em`) em vez de reusar `recusada_em` | Criaria um terceiro estado que nem `ck_entrega_falida_recusada_sem_missao` nem a invariante de ocupação de `MigracaoTest` conhecem, obrigando a alterar as duas. Para o resto do sistema os dois casos são o mesmo fato: a encomenda não entrou na custódia. |
| CHECK de coerência entre `fonte_pote` e `categoria` no banco | Reprovaria os INSERTs dos próprios seeds, que rodam DEPOIS da migration (faixa 900+) e não podem ser editados sem quebrar o checksum de todo banco de dev existente. A coerência mora no construtor de `Missao`, que é ponto único. |
| Converter `valor_ofertado_brl` em token por uma taxa | Uma taxa BRL→token aplicada sistematicamente É uma cotação, e cotação tem dois lados: quem sabe quantos tokens valem um real sabe quantos reais vale um token. O ADR 0009 §6 recusa a cotação token→real porque token conversível é dinheiro — com KYC e enquadramento regulatório junto. Ver §2b. |
| Aporte sem `Idempotency-Key` | Num endpoint que EMITE moeda, um retry de rede cunharia duas vezes — e a duplicata não seria detectável depois, porque ledger e projeção ficariam ambos errados na mesma direção e a reconciliação seguiria verde. |
