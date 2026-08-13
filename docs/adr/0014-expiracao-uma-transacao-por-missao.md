# 0014 — Expiração de missões: uma transação por missão

**Data:** 2026-08-11
**Status:** Aceito

---

## Contexto

O job de expiração processava um LOTE inteiro (200 missões) numa única transação. Duas falhas
distintas nasciam desse desenho, e nenhuma delas era visível em teste:

**1. Deadlock (40P01).** O estorno de pote trava as carteiras dos financiadores em ordem crescente
de id — regra global do projeto, respeitada por `FinanciamentoCarteiraService`. Mas com N missões na
mesma transação, a ordenação é local por missão e **não global pela transação**. Com duas missões de
financiadores cruzados (M1 financiada por `cB`, M2 por `cA`, `cA < cB`), a transação do job adquiria
`cB` e depois `cA` — ordem decrescente. Uma transferência P2P concorrente entre as mesmas carteiras
adquire `cA` e depois `cB`, na ordem certa. Ciclo fechado; o PostgreSQL mata uma das duas.

O comentário no código afirmava que "a ordem global de lock é respeitada porque `buscarAbertasVencidas`
já travou a missão". Isso cobre `missao → carteira`; não cobre `carteira (id crescente)`, que é a
outra metade da regra.

**2. Item envenenado.** Sem `try/catch` por item, uma única missão cujo `pote_tokens` diverge da soma
dos financiamentos (`IllegalStateException` em `EstornoFinanciamentoService`) derrubava a transação
inteira. Todas as outras perdiam a expiração, e o job reencontrava a mesma missão a cada 5 minutos —
indefinidamente. `DrenadorOutboxService` já tinha esse isolamento e o justificava; a expiração nunca
recebeu.

---

## Decisão

**Uma transação por missão.** `expirarLote` deixou de existir e deu lugar a `candidatas(...)` (sem
lock, fora de transação de escrita) mais `expirarUma(...)` (`@Transactional`, uma missão). O laço vive
em `ExpiracaoMissoesJob.varrer`, que **não** é transacional.

Dentro de `expirarUma` só existe uma missão travada, então as carteiras estornadas são só as dela — e
`estornarFinanciadores` já as ordena por id crescente. A ordem global volta a valer por construção.

O laço isola cada item com `try/catch`, no molde de `DrenadorOutboxService`.

**O laço precisa estar no job, não no serviço.** Um laço dentro do bean chamaria `expirarUma` por
`this`, sem passar pelo proxy do Spring: o `@Transactional` seria ignorado e cada missão rodaria sem
transação nenhuma — o defeito mais silencioso possível, porque tudo continuaria "funcionando".

**Isto não viola a proibição de `REQUIRES_NEW`.** A proibição existe porque a transação externa segura
`FOR UPDATE` e a interna pediria uma segunda conexão simultânea; com N ≥ tamanho do pool, deadlock de
pool. Aqui **não há transação externa**: cada missão abre e fecha a sua, uma conexão por vez. É o
mesmo formato de `MissaoService.aplicar` chamado por um controller.

---

## Consequências

**Positivas:**
- O deadlock some por construção, não por disciplina — não há mais como duas carteiras serem travadas
  fora de ordem na mesma transação.
- Uma missão inconsistente falha sozinha, é logada com o id, e a varredura continua.
- Contenção despenca: antes um lote de 200 missões financiadas segurava centenas de linhas de
  `carteira` do início ao fim; agora cada lock dura milissegundos.

**Negativas / trade-offs:**
- Um lote deixa de ser atômico. Não há regra que exija isso — a atomicidade que importa é por missão
  (status + trilha + estorno), e ela está preservada.
- Mais commits (~200 por lote em vez de 1). Irrelevante perto do ganho de contenção.
- O cursor é keyset, não offset: mais código do que `PageRequest.of(0, n)`. É obrigatório — com
  transação por missão, a que falha continua elegível e reapareceria no topo do lote seguinte, e o
  job repetiria a mesma falha até o teto. O cursor garante que cada candidata é tentada uma vez por
  execução.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Manter o lote e ordenar TODAS as carteiras globalmente antes de estornar | Exigiria descobrir o conjunto de carteiras de todas as N missões antes de qualquer estorno — informação que só `carteira` tem. Viraria um método na porta `EstornoPote` devolvendo carteiras para `missoes` travar: gerência de lock atravessando fronteira de módulo. Ainda seguraria centenas de locks pela duração do lote. E **não resolve o item envenenado**. |
| `REQUIRES_NEW` por missão dentro da transação do lote | Proibido no caminho de valor. Foi exatamente o que derrubou o pool no check-in da F6 e levou até o login junto. |
| Paginação por offset em vez de cursor keyset | A missão que falha continua `ABERTA` e vencida; com `OFFSET 0` ela reaparece na primeira posição do lote seguinte, e o job repete a falha até o teto por execução. O item envenenado voltaria em outra forma. |
| Deixar o `try/catch` sem cursor keyset | Mesma coisa: isola a falha dentro do lote, mas não impede a reprocessagem infinita entre lotes. |
