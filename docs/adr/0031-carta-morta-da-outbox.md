# 0031 — Carta-morta da outbox: consulta de esgotados e reenfileiramento por ADMIN

**Data:** 2026-09-10
**Status:** Aceito

---

## Contexto

`OutboxRepository.buscarPendentesParaPublicar` filtra por três condições, e a terceira é a que cria
o problema:

```java
where o.publicadoEm is null
  and o.proximaTentativaEm <= :agora
  and o.tentativas < :maximoTentativas
```

Com `app.outbox.maximo-tentativas: 5` e backoff exponencial de base 30 s (30 s, 1 min, 2 min, 4 min,
8 min), a quinta falha de despacho leva `tentativas` a 5 e a linha **sai do predicado para sempre**.
Ela não é apagada: fica na tabela com `publicado_em = NULL`, `tentativas = 5` e `ultimo_erro`
preenchido. O comportamento é selado por teste desde a F7 —
`DrenadorOutboxServiceTest.eventoQueEsgotouAsTentativasSaiDoLote`.

Até esta decisão, **nada no sistema mostrava essa linha.** `OutboxRepository` tinha uma única query,
a do lote. Não havia consulta por esgotados, endpoint de administração, métrica ou job de relatório.
O único vestígio era o `log.warn` da última falha (`DrenadorOutboxService.java:85`) — e Prometheus e
Grafana foram cortados do MVP de propósito (`CLAUDE.md`, Escopo), então ninguém o coleta.

A consequência é concreta e tem nome. `MissaoService` publica `MissaoConcluida` na mesma transação
do crédito (`MissaoService.java:1010`). Se o despachante não conseguir tratar o payload cinco vezes,
o executor **recebeu o token e nunca é avisado**, e não existe lugar onde esse fato apareça. É a
mesma forma do defeito econômico que `docs/EVOLUCAO-ARQUITETURAL.md` narra: consistência intacta,
fato perdido, e o instrumento que existiria para achá-lo respondendo verde.

O agravante foi de documentação, e já foi corrigido separadamente. A varredura de 2026-08-20
(`docs/auditoria/varredura-orfaos.md` §1.1) encontrou **três** afirmações de uma garantia
inexistente — *"retry até conseguir"*, *"entrega at-least-once"* e *"espera intervenção"* —, e a
varredura de 2026-09-09 encontrou uma quarta. Os quatro textos foram acertados na época; a lacuna
ficou aberta de propósito, porque fechá-la muda o contrato de entrega de notificação e isso é
decisão de projeto, não de implementação.

As forças em jogo:

- **Um evento esgotado é um fato perdido, não um dado inconsistente.** A reconciliação de carteiras
  responde `integro=true` o tempo todo enquanto isso acontece — são invariantes diferentes, como
  registra o `CLAUDE.md` de `services/api`.
- **Sem broker, o único transporte é a tabela.** Não há DLQ de infraestrutura para herdar: se
  houver carta-morta, ela é uma consulta e um endpoint escritos aqui.
- **O drenador não pode ficar mais esperto.** Aumentar `maximo-tentativas` só empurra o problema, e
  tentar para sempre transforma um payload envenenado em varredura perpétua contra o mesmo destino.
- **`ultimo_erro` já existe desde a V14** e guarda a causa da última falha, truncada em 1000
  caracteres. O diagnóstico está gravado; faltava caminho para lê-lo.

---

## Decisão

Adotamos **carta-morta consultável com reenfileiramento manual por ADMIN**, em `compartilhado`,
**sem migration**.

### 1. `GET /api/v1/admin/outbox/esgotados`

Consulta paginada (`PaginaResponse`) dos eventos que esgotaram as tentativas:
`publicado_em IS NULL AND tentativas >= :maximoTentativas`. Devolve `id`, `tipoEvento`,
`agregadoId`, `criadoEm`, `tentativas`, `proximaTentativaEm` e `ultimoErro`. `totalElementos` é a
contagem — o número que antes não existia em lugar nenhum.

**O `payload` fica fora da resposta.** `EntregaFalidaConvertida` carrega `lat`/`lon` de endereço
residencial que veio da transportadora, e `MissaoConcluida` carrega `executorId` e o valor
creditado. A lista é um índice de diagnóstico — *o quê falhou, quando, e por quê* —, não um dump.
Quem precisar do corpo tem o `id` e o `psql`. É restrição consciente: quem diagnostica pelo endpoint
enxerga a causa (`ultimo_erro`) e não o dado que a produziu.

### 2. `POST /api/v1/admin/outbox/{id}/reenfileirar`

Devolve a linha ao predicado do lote: `tentativas = 0`, `proxima_tentativa_em = agora`, **e
`ultimo_erro` PRESERVADO** — limpá-lo apagaria a única razão pela qual o evento parou.

**Quem entrega continua sendo o `DrenadorOutboxService`.** O endpoint não despacha; ele só torna a
linha elegível de novo, e o `DrenadorOutboxJob` a pega na varredura seguinte
(`app.outbox.intervalo`, 10 s). Despachar dentro do request seria o atalho que este ADR recusa:
`drenarLote` opera sobre o lote inteiro e não sobre uma linha, a entrega passaria a rodar na
transação do ADMIN, e o resultado do despacho viraria o status HTTP de uma operação administrativa —
um destino fora do ar devolveria 500 para quem só pediu "tente de novo".

### 3. A idempotência é estrutural, e por isso não há `Idempotency-Key`

O alvo é uma linha nomeada na URL e o efeito é levá-la a um estado fixo. Aplicar duas vezes dá o
mesmo estado; não há lançamento a inserir, não há emissão, não há segunda linha possível. É o mesmo
desenho de `ResgateService.darBaixa` (`PATCH /admin/resgates/{id}`), idempotente por estado terminal
e sem chave de cliente. Exigir `Idempotency-Key` aqui seria cerimônia sem corrida para fechar — e a
chave existe, no resto do projeto, para impedir que um retry de rede **crie uma segunda linha** no
ledger, que é exatamente o que aqui não pode acontecer.

A ordem canônica do projeto é respeitada, com a sondagem no meio:

1. **403** — `@PreAuthorize("hasRole('ADMIN')")`, antes de qualquer leitura que revele estado.
2. **Lock** — `buscarParaAtualizar(id)` com `PESSIMISTIC_WRITE`, primeira leitura da transação. É
   ele que fecha a corrida entre sondar e escrever, e é ele que serializa o endpoint contra o
   próprio drenador, que trava a mesma linha com `SKIP LOCKED`. Ausente → 404.
3. **Sondagem sob o lock**, três ramos:
   - `publicado_em` não nulo → **409**. O evento foi entregue; reenfileirá-lo produziria entrega
     duplicada. Não é replay, é conflito de estado, e responder 200 aqui faria um ADMIN acreditar
     que recuperou algo.
   - `publicado_em` nulo e `tentativas < máximo` → **200 com `reenfileirado: false`, sem escrita
     nenhuma.** Cobre os dois casos que dão no mesmo: o replay do próprio POST e o evento ainda em
     backoff. Nos dois a linha já vai ser tentada de novo, e mexer em `proxima_tentativa_em` aqui
     atropelaria o backoff — que não é o que este endpoint existe para fazer.
   - `publicado_em` nulo e `tentativas >= máximo` → segue.
4. **Gravação** — `Outbox.reenfileirar(agora)`, e **200 com `reenfileirado: true`**.

### 4. A escrita é auditada nas duas metades

`@Auditavel(acao = "OUTBOX_REENFILEIRADA", entidade = "outbox")` no método de serviço, e
`ReenfileiramentoResponse implements RecursoAuditavel` devolvendo o id do evento. Sem a segunda
metade o `AuditoriaAspecto` gravaria `entidade_id` nulo e a trilha diria "alguém reenfileirou algo"
— é o defeito que `ResultadoRegistroCheckin` teve e que
`RegrasArquiteturaTest.todo_metodo_auditavel_devolve_um_recurso_auditavel` passou a barrar.

### 5. Sem migration, e sem índice novo

As quatro colunas de que a decisão precisa existem desde a V7 (`publicado_em`, `tentativas`) e a
V14 (`proxima_tentativa_em`, `ultimo_erro`), e `auditoria.acao`/`auditoria.entidade` são
`VARCHAR(100)` sem CHECK. `idx_outbox_pendente` é parcial em `(proxima_tentativa_em) WHERE
publicado_em IS NULL` e **não cobre `tentativas >= 5`** — a consulta de esgotados usa a parcialidade
do índice e filtra o resto, ou varre. Não criamos índice dedicado: seria manutenção em **toda**
escrita da outbox para servir uma consulta feita à mão sobre um conjunto que, quando o sistema está
saudável, é vazio.

---

## Consequências

**Positivas:**

- **O fato perdido passa a ter endereço.** Antes, um `MissaoConcluida` abandonado só era
  encontrável por quem já suspeitasse dele e soubesse escrever a query. Agora tem contagem, lista e
  causa.
- **A recuperação acontece dentro da aplicação.** A alternativa real, hoje, é `UPDATE outbox SET
  tentativas = 0` no `psql`: sem ator, sem IP, sem `correlationId`, sem lock contra o drenador e sem
  linha em `auditoria`. O endpoint troca isso por uma operação com trilha.
- **Nenhum acoplamento novo entre módulos.** Tudo vive em `compartilhado`, que já é o dono da
  outbox e já é dependência de todos. `RegrasArquiteturaTest` não precisou de uma linha de mudança.
- **Nenhuma migration**, logo nenhum `make reset` em banco de dev existente.
- **Quatro comentários passam a descrever o sistema real.** `PublicadorEventos`,
  `DespachanteAlertaService`, `AlertaController` e `application.yml` diziam que o evento esgotado
  "espera intervenção"; agora existe a intervenção que eles descreviam.

**Negativas / trade-offs:**

- **Reenfileirar apaga da linha o fato de que ela já queimou cinco despachos.** `tentativas` volta a
  0, e a história passa a existir só em `ultimo_erro` (que é a causa, não a contagem) e nas linhas de
  `auditoria`. Um evento reenfileirado três vezes é indistinguível, olhando a tabela `outbox`, de um
  evento que falhou uma vez. Preservar a contagem exigiria coluna nova, e não vale uma migration.
- **A `auditoria` também NÃO dá essa contagem, e é preciso dizer isso em voz alta.** O
  `AuditoriaAspecto` é `@AfterReturning`: ele grava igual quando o método devolve
  `reenfileirado: false`. Medido no ciclo abaixo — dois POSTs, **um** reenfileiramento de fato,
  **duas** linhas em `auditoria`. O discriminador é o campo da resposta, e ele não é persistido.
  Contar linhas de `auditoria` superestima quantas vezes o evento voltou à fila.
- **A entrega continua não sendo at-least-once, e o texto do projeto tem de continuar dizendo isso.**
  O teto de cinco tentativas não mudou. O que mudou é que o evento esgotado ficou visível e
  recuperável — mas a recuperação **depende de um humano consultar o endpoint**. Um evento pode
  continuar não entregue indefinidamente se ninguém olhar. Trocamos "perda silenciosa" por "perda
  detectável", não por "entrega garantida", e afirmar o contrário reintroduziria exatamente o
  comentário falso que a varredura de 2026-08-20 encontrou.
- **Nada avisa que há evento esgotado.** Não há push, e-mail, badge nem métrica — é consulta ativa,
  e portanto tem o mesmo modo de falha do endpoint de reconciliação: só é consultado por quem já
  desconfia. Fechar isso exigiria o canal de notificação que o MVP não tem.
- **O botão pode dar falsa sensação de recuperação.** Se a causa é o payload — o caso mais provável,
  já que `DespachanteAlertaService.despachar` lança `IllegalStateException` em tipo de evento
  desconhecido —, reenfileirar gasta mais cinco despachos e volta ao mesmo lugar. O endpoint não
  distingue falha transitória de falha permanente, e nada limita quantas vezes um ADMIN pode
  insistir.
- **Superfície administrativa de escrita nova**, com o custo fixo que ela traz: `@PreAuthorize`,
  auditoria, teste de 403 e de 401, e uma corrida a mais para pensar (o endpoint disputa a linha com
  o drenador, e é o `FOR UPDATE` que resolve).
- **Sem o `payload`, o diagnóstico é parcial.** Quem investiga pelo endpoint vê a causa e não vê o
  dado que a produziu, e para a metade dos casos isso vai obrigar um `psql` de qualquer jeito. É o
  preço de não pôr coordenada residencial numa listagem HTTP.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| **Só a consulta, sem reenfileiramento** (opção (a) avaliada nesta decisão) | Troca "invisível" por "visível e irrecuperável". O ADMIN passaria a enxergar o evento perdido sem nada a fazer com ele dentro da aplicação, e a recuperação continuaria sendo `UPDATE` manual no `psql` — sem lock contra o drenador, sem ator e sem linha em `auditoria`. Metade do custo pela metade do valor. |
| **Aceitar a perda, com contador** (opção (c)) | Um `Counter` do Micrometer diz **quantos**, nunca **quais**: `outbox.esgotados=3` não permite saber qual executor ficou sem aviso nem reconstruir o evento, e para isso alguém vai ao `psql` do mesmo jeito. Pior, sem Prometheus — cortado do MVP de propósito — a métrica é estado do processo em memória e **zera a cada reinício**. Formalizaria a perda e continuaria sem instrumento de diagnóstico, que é exatamente a queixa da pendência do pote imobilizado. |
| **Despachar em linha no endpoint** (chamar `drenarLote` de dentro do request) | `drenarLote` opera sobre o lote inteiro, não sobre uma linha: reenfileirar um evento entregaria de carona todos os outros pendentes na transação do ADMIN. E o resultado do despacho viraria o status HTTP — um destino fora do ar devolveria 500 a quem só pediu "tente de novo", e o retry do ADMIN passaria a ser o retry do despacho. O padrão outbox existe justamente para separar essas duas coisas. |
| **Retentar para sempre, removendo o teto** | Transforma payload envenenado em varredura perpétua contra o mesmo destino. `DespachanteAlertaService.despachar` lança em tipo de evento desconhecido, e um evento nessa condição nunca vai passar: ele ficaria voltando a cada 8 min (o backoff satura) para sempre. O teto existe por um motivo e continua valendo. |
| **Aumentar `maximo-tentativas` de 5 para um número grande** | Empurra o problema sem resolvê-lo: continua havendo um N depois do qual o evento some em silêncio, e o número maior só torna mais raro alguém encontrar o caso. Além disso o backoff é exponencial e satura em 8 min por tentativa, então "20 tentativas" é ~2h de retry para depois perder do mesmo jeito. |
| **Tabela `outbox_morta` separada, com o evento movido para lá** | Duas fontes de verdade para o mesmo evento, e o `id` deixaria de ser estável entre elas. A informação que a tabela nova teria — que o evento esgotou — já é expressa exatamente por `publicado_em IS NULL AND tentativas >= 5` na tabela que existe. Migration, INSERT/DELETE transacional no drenador e uma segunda entidade, para materializar um predicado. |
| **Coluna `reenfileiramentos` para preservar a contagem histórica** | Uma migration (V28) e uma coluna mantida em toda escrita da outbox para um dado que a linha de `auditoria` já registra, com ator, IP e `correlationId` de brinde. Se um dia a contagem virar consulta frequente, a decisão se reabre — e aí a `auditoria` diz quantas vezes aconteceu antes, o que é o insumo para decidir. |
| **`Idempotency-Key` obrigatório no reenfileiramento** | A chave existe no projeto para impedir que um retry de rede **crie uma segunda linha** no ledger. Aqui não há linha a criar: o efeito é um UPDATE para um estado fixo, sobre um alvo nomeado na URL, sob `FOR UPDATE`. A segunda chamada é no-op por construção, e a sondagem sob o lock já a reconhece. Exigir a chave adicionaria um `ChaveIdempotencia.*` e uma coluna a consultar para fechar uma corrida que não existe. |
| **Expor o `payload` na listagem** | `EntregaFalidaConvertida` carrega `lat`/`lon` de endereço residencial de destinatário. Uma listagem HTTP paginada é o pior lugar para esse dado: fica em log de acesso, em histórico de navegador e em qualquer proxy no caminho — a mesma razão pela qual `POST /logistica/previsao-falha` é POST sem escrita. O `id` na resposta é o suficiente para quem tem acesso ao banco. |


---

## Evidência executada

### Plano da consulta de esgotados

Bancada montada à mão no banco de dev (`postgis/postgis:16-3.5`), com **50.000** linhas publicadas,
**200** pendentes em backoff e **3** esgotadas, `ANALYZE` rodado antes de medir. Dados sintéticos —
a outbox de dev estava vazia, e um plano sobre tabela vazia não diz nada. Apagados depois.

```
EXPLAIN (ANALYZE, BUFFERS)
SELECT o.id, o.tipo_evento, o.agregado_id, o.criado_em, o.tentativas, o.proxima_tentativa_em,
       o.ultimo_erro
FROM outbox o
WHERE o.publicado_em IS NULL AND o.tentativas >= 5
ORDER BY o.criado_em ASC LIMIT 20 OFFSET 0;

 Limit  (cost=56.69..56.70 rows=1 width=86) (actual time=0.034..0.035 rows=3 loops=1)
   Buffers: shared hit=10
   ->  Sort  (cost=56.69..56.70 rows=1 width=86) (actual time=0.034..0.034 rows=3 loops=1)
         Sort Key: criado_em
         Sort Method: quicksort  Memory: 25kB
         Buffers: shared hit=10
         ->  Index Scan using idx_outbox_pendente on outbox o
               (cost=0.14..56.68 rows=1 width=86) (actual time=0.007..0.023 rows=3 loops=1)
               Filter: (tentativas >= 5)
               Rows Removed by Filter: 200
               Buffers: shared hit=7
 Planning Time: 0.190 ms
 Execution Time: 0.063 ms
```

E a contagem que a paginação emite:

```
 Aggregate  (cost=56.68..56.69 rows=1 width=8) (actual time=0.028..0.028 rows=1 loops=1)
   ->  Index Scan using idx_outbox_pendente on outbox o
         (cost=0.14..56.68 rows=1 width=16) (actual time=0.011..0.026 rows=3 loops=1)
         Filter: (tentativas >= 5)
         Rows Removed by Filter: 200
         Buffers: shared hit=7
 Execution Time: 0.055 ms
```

**É exatamente o comportamento que a decisão §5 previu, e vale ler o que ele NÃO diz.** O índice
parcial faz o trabalho pesado — as 50.000 linhas publicadas nunca são tocadas —, e o
`Filter: (tentativas >= 5)` descarta as 200 pendentes que ele não consegue separar,
`Rows Removed by Filter: 200`. O custo do filtro é proporcional ao número de eventos **pendentes**,
não ao tamanho da tabela; ele só passaria a doer se houvesse dezenas de milhares de eventos em
backoff ao mesmo tempo, que é uma condição bem pior que uma consulta lenta.

### Ciclo ponta a ponta contra o servidor de pé

Backend em `spring-boot:run -Dspring-boot.run.profiles=dev`, banco descartável, `MissaoConcluida`
com `tentativas = 5` e `publicado_em` nulo — um executor creditado e não avisado.

```
1) usuário comum
   GET  /admin/outbox/esgotados          -> 403
   POST /admin/outbox/{id}/reenfileirar  -> 403

2) ADMIN, GET /admin/outbox/esgotados
   {"conteudo":[{"id":"42037c4f-…","tipoEvento":"MissaoConcluida","tentativas":5,
     "ultimoErro":"java.lang.IllegalStateException: Nenhum despachante para o evento X."}],
    "totalElementos":1}

3) POST #1 -> {"tentativas":0,"reenfileirado":true}
   POST #2 -> {"tentativas":0,"reenfileirado":false}

4) logo depois do POST: tentativas=0, publicado_em ainda NULL, ultimo_erro preservado,
   alertas do executor = 3 (inalterado) — o endpoint não despachou nada

5) depois da varredura do drenador (~4 s): publicado_em preenchido, ultimo_erro limpo,
   alertas do executor = 4 — a entrega veio pelo caminho de sempre

6) POST num evento já entregue -> 409
   {"type":"https://omnitribo.dev/problemas/transicao-invalida",
    "detail":"Evento já foi entregue em 2026-09-10T22:04:06.764151Z e não pode ser reenfileirado."}

7) GET /admin/outbox/esgotados -> totalElementos = 0

8) SELECT … FROM auditoria WHERE entidade='outbox'
   OUTBOX_REENFILEIRADA|outbox|42037c4f-…|ator=t|ip=t
   OUTBOX_REENFILEIRADA|outbox|42037c4f-…|ator=t|ip=t
```

O passo **4** é o que separa esta decisão de um despacho em linha: entre o POST e a varredura o
evento está na fila e **não** entregue. O passo **8** é a consequência negativa medida — dois POSTs,
um reenfileiramento real, duas linhas de auditoria.


### A suíte foi sabotada para provar que tem dentes

Verde não prova nada sozinho. Cada sabotagem abaixo foi aplicada ao código de produção, a suíte
rodou, e o arquivo foi restaurado. **As seis ficam vermelhas.** As três primeiras foram encontradas
por uma revisão: a suíte original ficava **verde** nelas.

| Sabotagem | Antes da revisão | Hoje |
|---|---|---|
| `buscarParaAtualizar` sem `@Lock` | vermelha (10 reenfileiramentos em vez de 1) | vermelha, em 2 testes |
| `buscarParaAtualizar` **com SKIP LOCKED** | **VERDE** — 9 de 10 ADMINs levavam 404 e ninguém via | vermelha, em 2 testes |
| consulta usa `>= teto - 1` | **VERDE** | vermelha |
| guarda usa `< teto - 1` | **VERDE** | vermelha |
| teto do drenador e da consulta desalinhados | **VERDE** | vermelha |
| `buscarEsgotados` sempre vazia | verde em 1 dos 2 testes de consulta | vermelha, em 3 testes |

**A mais instrutiva é a segunda, e o motivo não é o lock — é o `catch`.** O teste de concorrência
fazia `catch (Exception e)` dentro da tarefa submetida ao pool, e
`andExpect(status().isOk())` falha com `AssertionError`, que **não é** `Exception`. O `Future` do
`submit` nunca era inspecionado, então o erro desaparecia. A asserção
`as("nenhuma requisição pode falhar")` existia, era lida como rede de segurança, e **não podia
falhar**. Hoje o teste captura `Throwable`, coleta o status de cada thread e exige as dez respostas
`200` — o desfecho das perdedoras, e não só o da vencedora.

**A terceira e a quarta são o 49 m e 51 m do check-in, de novo.** A suíte exercitava `tentativas = 2`
e `tentativas = teto`, nunca `teto - 1` — o único valor que distingue `>=` de `>` deslocado por um.
JaCoCo dava **100% de branch** em `OutboxAdminService` com os dois defeitos vivos, e o profile
`mutacao` não alcança `compartilhado.dominio` (`targetClasses` cobre só `missoes.dominio` e
`carteira.dominio`), então o PIT também não os veria.

**A quinta é a que fecha o desenho.** Toda fixture de carta-morta é fabricada com `INSERT …
tentativas = <teto>`, o que fazia os dois lados afirmarem o mesmo número mágico sem que nenhum
observasse o outro. `eventoQueEsgotaNoDrenadorApareceNaCartaMorta` não menciona número nenhum: drena
até o **drenador** parar de tocar a linha — adiantando só o relógio do backoff, nunca o contador — e
então exige que a **consulta** a enxergue. É o único teste que reprova o desalinhamento dos dois
predicados, e é também o único que executa o ramo novo de `log.error`.
