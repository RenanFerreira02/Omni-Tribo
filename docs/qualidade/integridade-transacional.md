# Integridade transacional do ledger — evidência da F5

Data: 2026-08-07 · Branch `feat/f5-carteira-economia` · Build **verde com 266 testes**, 0 falhas.
Duas execuções completas consecutivas com números idênticos.

A documentação do projeto afirma que *"as transações de tokens têm atomicidade (ACID) e não toleram
perda ou reordenação"*. Este documento é a prova dessa afirmação: o que garante cada propriedade,
por que a alternativa foi descartada, e a saída real do teste que a verifica.

---

## Modelo, em uma frase

`lancamento` é a verdade e é append-only; `carteira.saldo_*` é projeção derivada, mantida na mesma
transação para leitura barata. Corrigir é ESTORNAR — linha nova com sinal oposto —, nunca UPDATE. O
banco revoga `UPDATE` e `DELETE` em `lancamento` para o role `omnitribo_app` desde a V5.

Toda mutação de saldo passa por **uma classe só**, `carteira/dominio/LivroRazaoService`. Nenhum outro
ponto do sistema escreve em `carteira.saldo_*`.

---

## A — Atomicidade

**Garantia.** Um crédito de conclusão envolve seis escritas: lançamento no ledger, projeção de saldo,
XP e nível do usuário, status da missão, trilha em `missao_evento`, e evento na outbox. Ou as seis
acontecem, ou nenhuma.

**Como.** Uma transação Spring só, sem `REQUIRES_NEW` em lugar nenhum do módulo. Todas as portas
entre módulos (`CreditoRecompensa`, `ProgressaoUsuario`, `FinanciamentoMissao`, `PublicadorEventos`)
são `MANDATORY` ou `REQUIRED` e rodam na transação do chamador.

**Por que não `REQUIRES_NEW`.** Já quebrou este repositório uma vez, e o incidente está documentado
no javadoc de `RegistroCheckinService` (branch F6): a transação externa segura `SELECT ... FOR
UPDATE` enquanto a interna pede uma SEGUNDA conexão. Com N requisições simultâneas e pool de tamanho
P, basta N ≥ P para todas as conexões ficarem presas esperando conexões que nunca virão — deadlock de
pool, 30 s de timeout e 500 para todo mundo, inclusive para quem só queria fazer login.
`application-test.yml` tem pool 20 contra testes de 50 e 100 threads, então o teste desta fase o
dispararia de imediato.

**Prova — `ConclusaoRollbackTest`.** Uma exceção é injetada DEPOIS do INSERT no ledger e ANTES do
commit, substituindo `PublicadorEventos` por `@MockitoBean`. Verificado: zero lançamentos, zero
outbox, zero trilha, status da missão inalterado, saldos e XP idênticos ao snapshot, zero auditoria
da confirmação.

Quatro detalhes fazem esse teste valer alguma coisa, e todos estão comentados na classe:

1. `MissaoService` injeta a **interface**, então o mock entra no lugar do bean real.
2. A publicação é a **última instrução dentro** do método transacional.
3. A classe de teste **não é `@Transactional`** — se fosse, o "rollback" seria só uma marca de
   rollback-only e as asserções leriam estado não commitado pela mesma conexão, passando pelo motivo
   errado.
4. `LivroRazaoService` usa **`saveAndFlush`, não `save`**. Com `save`, o Hibernate adiaria o INSERT
   para o flush do commit, o commit nunca aconteceria, e o teste afirmaria a ausência de uma linha
   que jamais foi tentada. É o detalhe mais importante do arquivo.

Um segundo teste na mesma classe reexecuta a operação com o publicador funcionando e confirma que a
tentativa falha **não envenenou a chave de idempotência**: a operação continua retentável e credita
exatamente uma vez. Um teste de rollback que só prova ausência é meio teste.

---

## C — Consistência

**Invariantes, e onde cada uma é imposta:**

| Invariante | Onde |
|---|---|
| `saldo_brl >= 0` e `saldo_tokens >= 0` | `ck_carteira_saldo_nao_negativo` (V13) |
| `valor_brl >= 0` e `valor_tokens >= 0` | `ck_lancamento_valores_nao_negativos` (V13) |
| Lançamento move alguma coisa | `ck_lancamento_valor_nao_nulo` (V13) |
| `pote_tokens >= 0` | `ck_missao_pote_nao_negativo` (V13) |
| Chave de idempotência única | `uk_lancamento_idempotencia` (V5) |
| `lancamento` imutável | `GRANT SELECT, INSERT` + `REVOKE UPDATE, DELETE` (V5) |

O sinal mora na coluna `sinal`, nunca no valor. Um `valor_brl` negativo numa linha DEBITO seria
contado como CRÉDITO pela soma do ledger (`SUM(CASE sinal WHEN 'CREDITO' THEN v ELSE -v END)`) e toda
reconciliação passaria a mentir sem produzir erro nenhum — por isso a constraint existe, e não só a
validação em Java.

**Débito tem três camadas**, deliberadamente redundantes: o serviço recusa com 422 sob o lock, antes
de qualquer escrita; `Carteira.debitar` lança `IllegalStateException` (erro de programação, 500); o
`CHECK` do banco é a última barreira. Se a segunda ou a terceira disparar, é defeito — e falhar alto
é melhor que saldo negativo silencioso num ledger financeiro.

**Conservação da moeda comunitária.** Concluir uma missão TRIBO/COLETA paga o executor **a partir do
pote financiado**, não com token cunhado. `SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)` é
invariante ao longo do ciclo inteiro. `FinanciamentoControllerTest` verifica a igualdade **a cada
etapa** — financiar, publicar, concluir —, não apenas no fim.

O estorno fecha o buraco que faltava: uma missão financiada e depois CANCELADA ou EXPIRADA deixaria
os tokens presos no pote para sempre. A soma continuaria fechando, mas uma parte dela estaria em
custódia inalcançável — na prática, o mesmo que queimar dinheiro dos outros.

**A revisão de segurança encontrou três furos nessa conservação, todos corrigidos e cobertos por
teste de regressão.** Vale registrá-los porque cada um mostra o mesmo padrão: a reconciliação
continuava respondendo `integro=true`, porque token sumindo do POTE não quebra a igualdade entre
ledger e projeção de carteira. Uma verificação de integridade só pega o que ela mede.

| Furo | Consequência | Correção |
|---|---|---|
| `ExpiracaoMissoesService` não passa por `MissaoService.aplicar`, então o estorno na expiração era **código morto** | Toda missão TRIBO publicada tem pote > 0; bastava ninguém aceitar até a janela vencer e os tokens dos financiadores sumiam | Estorno chamado dentro do lote de expiração, sob o lock que `buscarAbertasVencidas` já segura |
| Financiar acima da recompensa era permitido | A conclusão debita só `tokensRecompensa`; a sobra ficava presa, porque `CONCLUIDA` é terminal | 422 na entrada — o financiador descobre na hora que aquele token não é necessário |
| De `RASCUNHO` só se saía por `PUBLICAR` | Rascunho co-financiado e abandonado prendia tokens de terceiros para sempre | Transição `RASCUNHO --CANCELAR--> CANCELADA` (ADR 0006 revisto) |

**Reconciliação.** `GET /api/v1/admin/carteiras/reconciliacao` soma o ledger de toda carteira e
compara com a projeção. **Uma única statement SQL**, e essa é a decisão central: sob READ COMMITTED
cada statement enxerga um snapshot próprio, então somar e ler saldo em consultas separadas poderia
straddle um commit concorrente e reportar divergência FANTASMA. Numa statement só, o snapshot é o
mesmo por definição. `LEFT JOIN` e não `INNER`: uma carteira com saldo e ZERO lançamentos é a
corrupção mais grave possível e sumiria do resultado com `INNER JOIN`.

Essa verificação roda ao final de **todo** teste de concorrência, via
`SuporteCarteira.assertLedgerReconcilia`.

---

## I — Isolamento

**Nível adotado: READ COMMITTED** (padrão do PostgreSQL). Nenhum caminho de escrita declara
`isolation=`.

**O que READ COMMITTED permitiria e nos machucaria:** *lost update*. T1 lê saldo 100, T2 lê 100,
ambas escrevem 90 — um crédito desaparece.

**O que `SELECT ... FOR UPDATE` compra, e o isolamento sozinho não.** Esta é a parte que precisa ser
dita com exatidão. Sob READ COMMITTED, quando uma transação bloqueia num `FOR UPDATE` e o detentor
commita, o PostgreSQL **não** retoma com o snapshot antigo: ele faz **EvalPlanQual** — relê a versão
mais recente commitada da linha e reavalia os qualificadores contra ela. O esperador lê 90, não 100.
**`FOR UPDATE` não é apenas exclusão mútua: ele PROMOVE a leitura a leitura fresca.** É exatamente
essa propriedade que torna READ COMMITTED suficiente para o ledger — o saldo de que `saldo_apos_*` é
calculado é sempre a verdade commitada, nunca um snapshot obsoleto.

**Por que não REPEATABLE READ.** Em PostgreSQL, RR é snapshot isolation de verdade. Um `SELECT ...
FOR UPDATE` sobre linha alterada por transação concorrente **não** bloqueia-e-atualiza: aborta com
`ERROR: could not serialize access due to concurrent update` (SQLSTATE 40001). Toda carteira disputada
viraria falha de serialização que a aplicação teria de capturar e retentar. O teste de 100 threads
produziria ~99 abortos em vez de 99 replays limpos. Trocaríamos um bloqueio de microssegundos por um
laço de retry na aplicação — **estritamente pior para esta carga**.

**Por que não SERIALIZABLE.** SSI daria correção sem locks explícitos, a três custos: (a) os mesmos
`40001` sob contenção em carteira quente e no pote da missão; (b) memória de predicate lock, com
escalação para granularidade de página ou relação sob carga; (c) a consulta de teto por janela faz
uma leitura de FAIXA sobre `criado_em`, que é precisamente o tipo de leitura que faz o SSI abortar
transações vizinhas. E não removeria o `FOR UPDATE`, porque a ordenação determinística continua
necessária.

**Onde o isolamento maior seria necessário, e como evitamos.** Só na reconciliação, resolvido pela
statement única acima. A paginação do extrato tem anomalia conhecida (linha inserida entre o `count` e
o `select` desloca a página) — aceitável para lista de UI, documentado e não corrigido.

**Ordem global de lock: `missao` → `carteira` (id CRESCENTE) → `usuario`.** Nenhum caminho desvia.
Importa entre tabelas tanto quanto dentro delas: o financiamento quer `{carteira, missao}` e a
conclusão quer `{missao, carteira}` — se o financiamento pegasse a carteira primeiro,
financiar-vs-concluir na mesma missão daria deadlock.

**Por que ordenar elimina o deadlock.** Deadlock exige um CICLO no grafo de espera. Adquirindo sempre
em ordem crescente de uma ordem TOTAL, uma transação só pode esperar por outra que segura uma chave
estritamente MENOR do que a próxima que ela quer. A espera anda sempre numa direção; um ciclo exigiria
voltar. **Não é probabilidade reduzida — é impossibilidade por construção.**

Duas notas que parecem bug a quem revisa, e estão comentadas no código:

- `UUID.compareTo` compara os dois `long` internos **com sinal**, e essa ordem NÃO é a do tipo `uuid`
  no PostgreSQL. É irrelevante: a prova exige UMA ordem total consistente entre todos os call sites,
  não concordância com a do banco.
- Ordenamos por `carteira.id` — a PK da linha efetivamente travada — e não por `usuario_id`, para que
  a propriedade seja verificável lendo o SQL emitido.

Duas idas ao banco em vez de `where id in (:a, :b) order by id`: naquela forma o nó `LockRows` fica
ACIMA do nó de acesso que o planner escolher, e com bitmap scan a ordem de emissão das linhas — logo
a de aquisição dos locks — não é a do `ORDER BY`. O round-trip extra compra uma garantia; a query
única compra uma suposição sobre o planner.

**A armadilha da primeira leitura.** Se a `Carteira` já estiver no persistence context, o Hibernate
devolve a instância em cache **sem reemitir o `SELECT ... FOR UPDATE`** — o teste passa e o lock nunca
existiu. Por isso resolver `usuarioId → carteiraId` usa projeção escalar (`buscarIdPorUsuario`), não
`findByUsuarioId`: uma projeção não põe nada no contexto.

---

## D — Durabilidade

Commit do PostgreSQL com WAL — durabilidade é do banco, e nada na aplicação a contorna. O ponto
específico desta fase é a durabilidade da **notificação**, resolvida pelo Transactional Outbox.

**Por que a outbox existe.** Notificar de dentro da transação não é atômico com ela, e não existe
ordem que resolva:

- Push **antes** do commit anuncia um fato que o rollback pode desfazer. O usuário lê "R$ 18,00
  creditados" e o saldo não mudou.
- Push **depois** do commit perde o anúncio se a entrega falhar — e ela falha, porque é rede. O
  crédito aconteceu e ninguém foi avisado.

São dois sistemas sem transação em comum. A outbox move a decisão para o único lugar onde a
atomicidade existe: a MESMA transação grava o FATO (o lançamento) e a INTENÇÃO de anunciá-lo. Se a
transação some, a intenção some junto; se commita, a intenção está durável e um processo separado a
entrega com retry até conseguir.

Isso dá **entrega at-least-once sem broker de mensageria nenhum** — o Kafka/RabbitMQ que o escopo do
MVP cortou de propósito. O preço é at-least-once em vez de exactly-once: o consumidor precisa tolerar
receber o mesmo evento duas vezes.

O drenador usa **SKIP LOCKED** (`jakarta.persistence.lock.timeout = -2`) para que dois drenadores
peguem lotes disjuntos, e **backoff exponencial** (30 s, 1 min, 2 min, 4 min…) porque retentar sem
crescimento transformaria o retry em ataque contra um destino já fora do ar. Uma falha de despacho é
capturada por evento, não propagada: se um payload envenenado pudesse abortar a transação do lote, ele
pararia a fila inteira — o modo de falha mais caro que uma outbox pode ter.

---

## Idempotência — garantida pelo BANCO, não pela aplicação

**O mecanismo.** Sondagem sob o row lock, com `uk_lancamento_idempotencia` como barreira final que
nunca deve disparar. O argumento, na forma em que precisa sobreviver a ser defendido oralmente:

> Todo caminho capaz de produzir uma dada chave adquire `PESSIMISTIC_WRITE` sobre uma linha comum
> **antes** de sondar o ledger, e segura até o commit. Logo, duas transações que poderiam colidir na
> chave estão serializadas por esse lock, e a sondagem do perdedor roda estritamente depois do commit
> do vencedor e enxerga a linha dele. **Não existe janela entre sondar e inserir, porque a janela é
> fechada pelo lock, não pela sondagem.**

| Operação | Linha serializadora |
|---|---|
| Conclusão (`/confirmar`, `/resolver`) | `missao` |
| Transferência | `carteira` do remetente |
| Saque | `carteira` própria |
| Financiamento | `missao`, depois `carteira` do financiador |

Regra que vale para todo caminho novo: **adquira todos os locks → sonde → valide → escreva.**

**Nada captura `DataIntegrityViolationException`.** O no-op de replay é decidido ANTES do INSERT. Se a
constraint disparar, o invariante acima deixou de valer — é defeito, não corrida a recuperar, e sobe
como 500 com `log.error`. Capturar seria pior de duas formas: mascararia o defeito, e em Spring um
catch de violação de constraint dentro da transação já a marcou rollback-only, então o "tratamento"
produziria um commit impossível.

**Namespace da chave.** O valor gravado NUNCA é a chave do cliente: é
`sha256(operacao|ator|…)` em hex de 64 caracteres, derivado num lugar só
(`compartilhado/dominio/ChaveIdempotencia`).

- **O ator entra no material.** `uk_lancamento_idempotencia` é UNIQUE de coluna única e namespace
  GLOBAL. Com a chave crua, o `Idempotency-Key: 1` de um cliente colidiria com o `1` de todos os
  outros — e o segundo não receberia erro, receberia **o replay da transação de um estranho**.
  Vazamento entre usuários e perda silenciosa de uma operação na mesma linha de código.
- **A operação entra no material.** Senão a mesma chave num saque e numa transferência colidiria, e o
  saque viraria no-op respondendo 200 sem ter movido nada.
- **Valor e destinatário ficam FORA**, de propósito. Retry com a mesma chave é replay da operação
  original, não uma nova — o que fecha o buraco de "reenviar a mesma chave com valor maior cria uma
  segunda transferência". Verificado em
  `TransferenciaControllerTest.retryDaMesmaChaveComValorDiferenteNaoCriaSegundaTransferencia`.
- A conclusão é a exceção: chave natural é `(missaoId, executorId)`, sem header. `/confirmar` não tem
  `Idempotency-Key`, e a idempotência precisa valer inclusive para quem não manda nenhum. Como
  `CONCLUIDA` é terminal, idempotente-por-missão é exatamente a semântica desejada.

---

## Evidência — saída real de `./mvnw verify`

```
INFO c.o.c.api.CarteiraConcorrenteTest    - Carteira sob concorrência: 50 threads → 201: 50 | 422: 0 | 500: 0
INFO c.o.c.api.ConclusaoConcorrenteTest   - Conclusão concorrente com 100 threads → 200: 100 | 409: 0 | 422: 0 | 429: 0 | 500: 0
INFO c.o.c.api.SaqueConcorrenteTest       - Saque concorrente com 20 threads → 201: 1 | 422: 19 | 500: 0
INFO c.o.c.api.TransferenciaDeadlockTest  - Transferências cruzadas: 100 rodadas × 2 sentidos → 201: 200 | 422: 0 | 500: 0

[INFO] Tests run: 266, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  33.843 s
```

Por classe, nos testes da fase:

```
ChaveIdempotenciaTest ......... 8    ReconciliacaoTest ............. 8
RegraNivelTest ................ 18   TransferenciaControllerTest ... 10
DrenadorOutboxServiceTest ..... 5    CarteiraLeituraESaqueTest ..... 9
FinanciamentoControllerTest ... 14   ConclusaoRollbackTest ......... 2
ConclusaoConcorrenteTest ...... 1    CarteiraConcorrenteTest ....... 1
TransferenciaDeadlockTest ..... 1    SaqueConcorrenteTest .......... 1
```

### Como ler cada número

| Teste | O que o número prova |
|---|---|
| **Conclusão, 100 threads → 100 × 200** | Retry é replay, não conflito. Zero 409 significa que a sondagem de idempotência roda ANTES da validação de transição — quem perdeu a resposta na rede não recebe conflito. E o banco confirma: **1 lançamento**, BRL e tokens creditados uma vez, XP somado uma vez (100 concessões teriam dado 15 000), 1 evento na trilha, 1 na outbox. |
| **Carteira, 50 threads → 50 × 201** | 25 créditos e 25 débitos simultâneos na MESMA linha. Saldo final volta ao inicial e a reconciliação fecha: nenhum *lost update*. Um lost update quebraria a igualdade projeção = soma do ledger mesmo com todos os HTTP em 201. |
| **Transferências cruzadas, 200 × 201, zero 500** | A→B e B→A simultâneos, 100 rodadas. Um único 500 aqui seria `40P01` — deadlock. Zero é a prova da ordenação de locks. Cada rodada usa chave nova: reaproveitá-la transformaria as rodadas 2–100 em replays baratos que nunca pedem o segundo lock, e o teste passaria sem testar nada. |
| **Saque, 20 threads → 1 × 201 e 19 × 422** | Double-spend clássico: chaves de idempotência DIFERENTES, cada thread pedindo o saldo inteiro. Um vence, os outros são recusados com 422 — não 500 —, e o saldo termina zero, nunca negativo. Sem `FOR UPDATE`, todas leriam 100, todas passariam na verificação e o `ck_carteira_saldo_nao_negativo` derrubaria as perdedoras. |
| **Rollback, 2 testes** | Falha antes do commit não deixa resíduo em tabela nenhuma; e a operação continua retentável depois. |
| **Financiamento, 14 testes** | Conservação verificada a cada etapa, no cancelamento, na expiração e com dois financiadores. |
| **Reconciliação, 8 testes** | Que ela **consegue acusar** — saldo adulterado, lançamento órfão, carteira com saldo e zero lançamentos —, que carteira zerada não é falso positivo, e que o helper `assertLedgerReconcilia` de fato reprova. Sem esta classe, a asserção que fecha os sete testes de concorrência nunca tinha sido vista falhando. |

---

## O que esta fase NÃO garante

Registrado por honestidade, e porque uma banca pergunta:

> **Atualização de 2026-08-08 — o item do BRL desta lista foi RESOLVIDO, e como ele foi resolvido é
> a parte mais instrutiva deste documento.** O texto original está preservado logo abaixo, riscado,
> porque a previsão que ele fez se concretizou exatamente como escrita.

- ~~**O BRL não tem lastro — e isso é a lacuna mais séria da fase.**~~ O TOKEN tem sumidouro (o
  pote); o BRL não tinha nenhum. `missao.valor_brl` era escolhido livremente pelo criador e a
  conclusão creditava esse valor **sem nenhum débito correspondente em lugar nenhum**. O texto
  original alertava: *"o caminho está fechado por acidente, não por controle — `AGUARDANDO_CONFIRMACAO`
  só é alcançável via CHECKIN, que responde 501 até a F6. No dia em que a F6 implementar o check-in,
  isso vira impressora de dinheiro sem nenhuma mudança no módulo carteira."*

  **Foi o que aconteceu.** Medido depois da F6, contra a API em execução: R$ 118,00 viraram
  R$ 1.618,00 em três ciclos do fluxo feliz, com o saldo do criador intacto — e
  `GET /admin/carteiras/reconciliacao` respondendo `integro=true` o tempo todo, **corretamente**,
  porque ela compara ledger com projeção e o BRL não tinha invariante de conservação para violar.

  **Resolução (ADR 0009):** nenhuma das duas saídas propostas foi escolhida, porque as duas partiam
  da premissa errada. Quem cria a missão NÃO paga — nunca pagou, no modelo real do produto. O BRL
  saiu do ciclo de missões: `ck_missao_economia` (V15) exige `valor_brl = 0` em toda categoria, e o
  saque ficou atrás de flag desligada. A recompensa é XP + TOKEN, e desde a V16 é **calculada pelo
  servidor e congelada** com a versão da fórmula — antes disso o cliente escolhia o próprio valor, o
  que reencarnou o mesmo defeito no token (656 → 2.656 em dois ciclos).

  **A lição, que vale mais que a correção:** a reconciliação passou em todos esses cenários. Ela
  verifica *consistência*, não *conservação*, e as duas não são a mesma coisa. `ConservacaoTokensTest`
  existe para essa segunda invariante, e roda `assertLedgerReconcilia` nos dois ramos de propósito —
  para deixar executável a demonstração de que uma passa enquanto a outra é violada.
- **Exactly-once na notificação.** A outbox dá at-least-once. O consumidor precisa tolerar duplicata.
- **Saque não transfere dinheiro.** Registra o débito e devolve protocolo; a liquidação depende de
  gateway externo, fora do escopo do MVP. O débito ser gravado no pedido é intencional — senão a
  mesma quantia poderia ser sacada duas vezes na janela entre pedido e liquidação.
- **Idempotência sem fingerprint do corpo.** Reenviar a mesma chave com valor ou destinatário
  diferentes devolve `replay=true` e não executa a segunda operação. É a semântica segura, mas
  silencia um erro do cliente; o padrão da indústria devolveria 422 quando o corpo diverge da chave.
  O campo `replay` na resposta dá ao cliente como detectar.
- **IP da trilha de auditoria é falsificável.** `X-Forwarded-For` é confiado sem proxy reverso
  declarado. Não afeta rate limit nos endpoints de valor (a chave é o `sub` do JWT), mas o IP gravado
  em `auditoria` para um saque ou transferência é o que o cliente disser.
- **Paginação do extrato** tem anomalia sob escrita concorrente (descrita em Isolamento).
- **Missão ENTREGA/AJUDA ainda cunha tokens.** O sumidouro (o pote) cobre TRIBO e COLETA; nessas
  duas a conservação foi medida e fecha. **Continua em aberto DE PROPÓSITO até a F8**, e a razão
  importa: exigir pote de ENTREGA hoje faria membros da tribo custearem a logística do varejista, o
  inverso do modelo. O financiador correto é o PATROCINADOR — entrega que falhou custa re-entrega,
  armazenagem e risco de perder o cliente, então patrocinar o pote sai mais barato que o fracasso.
  Preferimos a lacuna documentada a uma regra errada codificada. O que a V16 fechou não foi a
  cunhagem: foi o **arbítrio do cliente sobre o tamanho dela**.
  `ConservacaoTokensTest` declara os dois regimes por categoria, então uma mudança acidental em
  qualquer um deles quebra o build.
- **Um único nó.** O rate limit é em memória e o drenador de outbox assume instância única (o SKIP
  LOCKED já o prepara para mais de uma, mas isso não foi testado com dois processos).
