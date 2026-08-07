# 0008 — Ledger append-only, idempotência sob lock e outbox transacional

**Data:** 2026-08-07
**Status:** Aceito

> Numeração: o 0007 está ocupado por
> [`0007-consultas-geoespaciais-centralizadas.md`](./0007-consultas-geoespaciais-centralizadas.md),
> da fase de geolocalização, que corre em branch paralela.

---

## Contexto

A F5 implementa o módulo `carteira`, e com ele a primeira operação de valor do sistema: concluir uma
missão credita BRL, tokens e XP. Até aqui nenhum caminho creditava nada — `MissaoService.confirmar`
era um stub 501, e a tabela `outbox` existia desde a V7 sem produtor nem consumidor.

A documentação do produto afirma que *"as transações de tokens têm atomicidade (ACID) e não toleram
perda ou reordenação"*. Essa é uma afirmação forte, e o protótipo Flutter descartado a violava de
forma grosseira: creditava a recompensa no ACEITE, permitindo aceitar e nunca executar com o dinheiro
já pago. Precisamos de um desenho que sustente a afirmação sob teste de concorrência real, não apenas
no caminho feliz.

Três forças específicas moldam a decisão:

1. **Retry é normal, não excepcional.** Um app móvel perde resposta e reenvia. Sem idempotência, um
   retry duplica crédito — e a `UNIQUE` de `chave_idempotencia`, sozinha, transforma isso num 500.
2. **A transferência P2P toca duas linhas de carteira**, e duas transferências em sentidos opostos
   entre as mesmas carteiras são um cenário canônico de deadlock.
3. **Notificar não é transacional.** Nenhuma ordem entre "commitar" e "enviar push" é atômica, e o
   escopo do MVP cortou broker de mensageria de propósito.

---

## Decisão

### 1. `lancamento` é a verdade; `carteira.saldo_*` é projeção derivada

Toda mudança de saldo é um INSERT no ledger seguido da atualização da projeção, na MESMA transação.
Correção é ESTORNO — linha nova com sinal oposto —, nunca UPDATE. O banco impõe: `GRANT SELECT,
INSERT` e `REVOKE UPDATE, DELETE` em `lancamento` para `omnitribo_app` desde a V5.

Um único ponto do sistema escreve saldo: `carteira/dominio/LivroRazaoService`.

Um endpoint admin de reconciliação soma o ledger e compara com a projeção, numa **única statement
SQL** — sob READ COMMITTED, duas statements poderiam straddle um commit concorrente e acusar
divergência fantasma. Essa verificação roda ao final de todo teste de concorrência.

### 2. Idempotência garantida pelo BANCO, com a corrida fechada pelo LOCK

Mecanismo: **sondagem sob o row lock**, com `uk_lancamento_idempotencia` como barreira final que
nunca deve disparar.

> Todo caminho capaz de produzir uma dada chave adquire `PESSIMISTIC_WRITE` sobre uma linha comum
> antes de sondar o ledger, e segura até o commit. Duas transações que poderiam colidir na chave
> estão portanto serializadas por esse lock, e a sondagem do perdedor roda estritamente depois do
> commit do vencedor e enxerga a linha dele. Não existe janela entre sondar e inserir, porque a
> janela é fechada pelo lock, não pela sondagem.

Regra que vale para todo caminho novo: **adquira todos os locks → sonde → valide → escreva.**

Nada captura `DataIntegrityViolationException`. Se a constraint disparar, o invariante deixou de
valer: é defeito, sobe como 500 com `log.error`.

A chave gravada nunca é a do cliente: é `sha256(operacao|ator|…)`, derivado em um lugar só
(`ChaveIdempotencia`). Ator e operação entram no material; valor e destinatário ficam fora.

### 3. Ordem global de lock: `missao` → `carteira` (id crescente) → `usuario`

Adquirindo sempre em ordem crescente de uma ordem total, uma transação só pode esperar por outra que
segura chave estritamente menor. A espera anda numa direção só; um ciclo exigiria voltar. Deadlock
fica **impossível por construção**, não improvável.

### 4. READ COMMITTED, com `SELECT ... FOR UPDATE`

Nenhum caminho de escrita eleva o isolamento. Sob READ COMMITTED, quando uma transação bloqueia num
`FOR UPDATE` e o detentor commita, o PostgreSQL faz **EvalPlanQual**: relê a versão mais recente
commitada e reavalia os qualificadores. `FOR UPDATE` não é só exclusão mútua — **promove a leitura a
leitura fresca**, e é isso que torna READ COMMITTED suficiente para um ledger.

### 5. Transactional Outbox para notificação

A mesma transação grava o FATO e a INTENÇÃO de anunciá-lo. Um `@Scheduled` drena com SKIP LOCKED e
backoff exponencial. Entrega at-least-once sem broker nenhum.

### 6. Conservação da moeda comunitária pelo pote

Missão TRIBO/COLETA é financiada por membros (débito na carteira, crédito em `missao.pote_tokens`) e
paga o executor **do pote**, sem cunhar token. `SUM(saldo_tokens) + SUM(pote_tokens)` é invariante.

Quatro regras sustentam essa invariante, e três delas só existem porque a revisão de segurança
mostrou que sem elas a conservação era falsa:

- Publicar missão comunitária exige pote já cobrindo a recompensa. Sem isso a missão chegaria em
  `AGUARDANDO_CONFIRMACAO` e nunca poderia ser concluída.
- Financiar **acima** da recompensa é recusado com 422. A conclusão debita exatamente
  `tokensRecompensa` e `CONCLUIDA` é terminal, então a sobra ficaria presa para sempre. Recusar na
  entrada é mais honesto que estornar resíduo na saída.
- **Cancelamento e expiração estornam o pote**, e o estorno precisa estar nos DOIS caminhos:
  `MissaoService.aplicar` (cancelamento) **e** `ExpiracaoMissoesService.expirarLote` (expiração). O
  job de expiração não passa por `aplicar`, então pô-lo só lá deixava o ramo de expiração como
  código morto — e a perda seria invisível para a reconciliação, porque ledger e projeção continuam
  batendo quando o token some do pote.
- `RASCUNHO --CANCELAR--> CANCELADA` foi acrescentada à máquina de estados (ADR 0006 revisto). Como
  o financiamento acontece antes da publicação, sem essa saída um rascunho co-financiado e
  abandonado prenderia tokens de terceiros permanentemente.

---

## Consequências

**Positivas:**

- Auditabilidade completa: todo centavo e todo token tem uma linha imutável que diz de onde veio,
  para onde foi e qual saldo resultou.
- Retry é seguro por construção, inclusive para clientes que não enviam `Idempotency-Key` (a
  conclusão deriva a chave da própria missão).
- Deadlock entre transferências cruzadas é impossível, não raro — verificado em 100 rodadas.
- Notificação nunca anuncia fato inexistente nem perde fato ocorrido, sem infraestrutura adicional.
- A economia de tokens é fechada: nada é cunhado no ciclo de uma missão comunitária.
- Um defeito de escrita futuro é detectável pela reconciliação, em vez de silencioso.

**Negativas / trade-offs:**

- **Duas escritas por operação** (ledger + projeção) em vez de um UPDATE. Aceito: a projeção é o que
  torna a leitura de saldo barata, e mantê-la na mesma transação impede divergência.
- **Contenção por linha de carteira.** Uma carteira muito ativa serializa suas operações. Aceito para
  a escala do MVP; a alternativa (saldo derivado sob demanda) trocaria contenção de escrita por custo
  de leitura em toda consulta.
- **Entrega at-least-once**, não exactly-once. O consumidor precisa tolerar duplicata.
- **Duas idas ao banco** para travar duas carteiras, em vez de uma query com `IN`. Justificado: o nó
  `LockRows` fica acima do nó de acesso escolhido pelo planner, e a ordem de emissão não é
  garantidamente a do `ORDER BY`.
- **Armadilha da primeira leitura** vira regra que todo caminho novo precisa conhecer: resolver
  `usuarioId → carteiraId` tem de usar projeção escalar, senão o Hibernate devolve a entidade em
  cache e o `FOR UPDATE` nunca é emitido.
- **`lancamento` cresce sem limite.** Não há arquivamento. Aceitável no horizonte do projeto.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|---|---|
| **Saldo como UPDATE direto, sem ledger** | Perde a auditabilidade que é o propósito do módulo. Uma correção viraria reescrita de história, e não haveria como detectar divergência depois — não existiria contra o que conciliar. |
| **`REQUIRES_NEW` para isolar o registro do lançamento** | **Já quebrou este repositório.** O javadoc de `RegistroCheckinService` documenta o incidente: a transação externa segura `FOR UPDATE` enquanto a interna pede uma SEGUNDA conexão; com N ≥ tamanho do pool, todas as conexões ficam presas esperando conexões que nunca virão — deadlock de pool e 500 para todo mundo, inclusive login. O pool de teste é 20 contra testes de 100 threads. |
| **`INSERT ... ON CONFLICT DO NOTHING` para a idempotência** | Resolve a metade errada. O que corre é a LEITURA do saldo para calcular `saldo_apos_*`, não o insert — o lock continuaria necessário, e aí o `ON CONFLICT` não compra nada, custa um `SELECT` a mais para descobrir quem venceu, e contorna o construtor de `Lancamento`. |
| **Capturar `DataIntegrityViolationException` como sinal de replay** | Em Spring, um catch de violação de constraint dentro da transação já a marcou rollback-only: o "tratamento" produziria um commit impossível. Além disso mascararia o defeito real — se a constraint disparou, a serialização por lock deixou de valer. |
| **`@Version` (lock otimista) em vez de `FOR UPDATE`** | O perdedor só descobre a colisão no flush, depois de o método de negócio ter retornado e de o INSERT já ter sido emitido para ser desfeito. Com `FOR UPDATE` ele bloqueia por microssegundos, relê o saldo commitado e decide certo na primeira tentativa. Mesmo raciocínio já registrado no ADR 0006 para o aceite. |
| **REPEATABLE READ** | Em PostgreSQL, `FOR UPDATE` sobre linha alterada concorrentemente não bloqueia-e-atualiza: aborta com `40001`. Toda carteira disputada viraria falha de serialização com retry na aplicação — o teste de 100 threads daria ~99 abortos em vez de 99 replays limpos. |
| **SERIALIZABLE** | Mesmos `40001` sob contenção, mais memória de predicate lock com escalação, e a consulta de teto por janela faz leitura de FAIXA sobre `criado_em` — exatamente o que faz o SSI abortar vizinhos. E não removeria o `FOR UPDATE`, porque a ordenação determinística continua necessária. |
| **Broker de mensageria (Kafka/RabbitMQ) para os eventos** | Cortado do escopo do MVP de propósito (CLAUDE.md, Escopo). A outbox dá a mesma garantia de entrega at-least-once usando a transação do banco que já existe, sem operar um serviço a mais. |
| **Ordenar locks pelo `usuario_id`** | Funcionaria, mas a linha travada é a de `carteira`. Ordenar pela PK da linha efetivamente bloqueada torna a propriedade verificável lendo o SQL emitido, em vez de exigir um passo mental de tradução. |
| **Travar as duas carteiras numa query só (`where id in (:a,:b) order by id`)** | O nó `LockRows` do PostgreSQL fica ACIMA do nó de acesso; com bitmap heap scan ou índice não ordenado, a ordem de emissão — logo a de aquisição — não é a do `ORDER BY`. A query única compra uma suposição sobre o planner; dois round-trips compram uma garantia. |
| **Endpoint `/concluir` novo, separado de `/confirmar`** | `/confirmar` já existia com o contrato exato (só criador, só de AGUARDANDO_CONFIRMACAO) e com a transição `CONFIRMAR → CONCLUIDA` declarada na máquina de estados desde a F4. Um endpoint novo exigiria um evento `CONCLUIR` duplicando `CONFIRMAR`, com dois caminhos para a mesma transição a partir do mesmo estado. |
| **Financiamento como sumidouro puro (tokens entram no pote e não saem)** | Seria sumidouro, mas o pote não teria uso e a conclusão continuaria cunhando token. Pagar o executor DO pote é o que torna a conservação real. |
| **Tesouraria na tribo em vez de pote na missão** | Casaria melhor com a rota `/tribos/{id}/financiamentos`, mas exigiria decidir quem autoriza saque da tesouraria e abriria a possibilidade de uma missão consumir fundos destinados a outra. O pote na missão vincula o dinheiro ao propósito para o qual foi dado. |
| **Deixar o beco sem saída do pote insuficiente para o admin resolver** | O caminho existe (CONTESTAR → EM_DISPUTA → RESOLVER_CANCELAR), mas exige intervenção humana num caso previsível. Bloquear a PUBLICAÇÃO falha cedo, quando o criador ainda pode financiar ou baixar a recompensa — depois de alguém ter executado a missão, não pode mais. |
