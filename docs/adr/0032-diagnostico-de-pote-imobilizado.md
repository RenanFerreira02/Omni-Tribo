# 0032 — Diagnóstico de pote imobilizado: a conservação ganha instrumento

**Data:** 2026-09-11
**Status:** Aceito

---

## Contexto

O projeto tem duas invariantes econômicas, e passou dois anos tratando uma como prova da outra:

| Invariante | O que compara | Quem responde |
|---|---|---|
| **Reconciliação** | `carteira.saldo_*` × soma do ledger daquela carteira | `GET /admin/carteiras/reconciliacao` |
| **Conservação** (ADR 0027) | `SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)` ao longo do ciclo | *(ninguém)* |

Token preso numa missão não-terminal parada **viola a segunda e deixa a primeira intacta**. Não é
sutileza: o financiamento debitou a carteira e creditou o pote na MESMA transação, com lançamento e
projeção escritos juntos. As duas somas continuam batendo exatamente. O que não existe é a viagem de
volta — o token saiu de uma carteira e não chegou em nenhuma outra.

O `EstornoFinanciamentoService` descreve essa perda como "economicamente idêntico a queimar dinheiro
de terceiros".

### O precedente que torna isto embaraçoso

O ADR 0015 já declarou esta visibilidade como consequência positiva, em 2026-08-11:

> ~~`MissaoRepository.potesImobilizados` dá visibilidade ao dinheiro que a reconciliação não acha.~~

Era falso. A consulta foi escrita e **nenhum serviço, endpoint ou teste jamais a chamou**. A
varredura de órfãos de 2026-08-20 a removeu e o ADR 0015 recebeu retificação. Desde então a lacuna
está registrada no `CLAUDE.md` sob o título "Nada acha pote imobilizado" — declarada, e aberta.

**A lição que este ADR carrega adiante: consulta de diagnóstico sem chamador é pior que lacuna
declarada, porque faz a lacuna parecer coberta.** Por isso a decisão abaixo nasce com dois
consumidores, e nenhum deles é opcional.

### O que a leitura do código acrescentou ao enunciado da pendência

`FinanciamentoService.validarEstado` recusa financiamento apenas em estado **terminal**, em
`CUNHAGEM` e em `PATROCINADOR` — e um comentário in-line afirma, corretamente, que **RASCUNHO é
financiável** (publicar missão comunitária exige pote, então o financiamento acontece necessariamente
antes da publicação). Logo o pote pode existir nos **seis** estados não-terminais, e não nos três que
o `CLAUDE.md` nomeia:

| Estado | Varredura por prazo | Porta de ADMIN | A query órfã olhava? |
|---|---|---|---|
| `RASCUNHO` | não | **nenhuma** | **não** |
| `ABERTA` | sim (`janela_fim`) | dispensável | **não** |
| `ACEITA` | não | **nenhuma** | **não** |
| `EM_ANDAMENTO` | sim (`estado_desde`, 48 h) | `DESTRAVAR` | sim |
| `AGUARDANDO_CONFIRMACAO` | sim (`estado_desde`, 72 h) | `DESTRAVAR` | sim |
| `EM_DISPUTA` | não | `RESOLVER_*` | sim |

A consulta que "daria visibilidade" era, além de órfã, **incompleta**: ignorava dois estados que
retêm pote e cuja única saída depende de uma pessoa específica aparecer.

### As forças em jogo

- **Um número sem os "quais" não aciona ninguém.** Saber que 450 tokens estão presos não diz sobre
  qual missão usar a porta de ADMIN.
- **Os "quais" sem o número não fecham a conta.** Uma página de 20 linhas não responde "quanto".
- **Quem olha a reconciliação não vai adivinhar que existe um segundo endpoint.** É ali, ao lado de
  `integro=true`, que a ressalva precisa estar.
- **Contar todo pote seria ruído.** Toda missão em execução segura um pote; isso é custódia normal,
  não perda.

---

## Decisão

**Adotamos um diagnóstico DETECTIVO em dois pontos — consulta paginada de ADMIN em `missoes` e campo
separado na reconciliação —, com uma migration, e sem tocar na máquina de estados.**

### 1. `GET /api/v1/admin/missoes/potes-imobilizados`

Devolve `resumo` (contagem, soma e o limiar que os produziu) **mais** a página de missões, ordenadas
da mais antiga para a mais recente. Vive em `missoes` porque é ali que a tabela `missao` mora — o
mesmo critério que pôs `OutboxAdminController` em `compartilhado`. Não há composição entre módulos
aqui, ao contrário do `ImpactoController`, que compõe quatro.

O resumo viaja junto da página de propósito: **o total não é derivável da página**, que traz no
máximo 100 das linhas. `totalElementos` responderia quantas missões, nunca quantos tokens.

Cada linha traz `missaoId`, `status`, `categoria`, `fontePote`, `poteTokens`, `tokensRecompensa`,
`paradaDesde`, `horasParada` e `varreduraCobre`. **Não traz título, criador nem coordenada** —
`missao.origem_lat`/`origem_lon` é endereço residencial, e listagem HTTP paginada vai para log de
acesso e para qualquer proxy no caminho. Mesmo argumento que tirou o `payload` de
`EventoEsgotadoResponse` (ADR 0031). Quem precisa do resto tem `GET /missoes/{id}`.

### 2. `potesImobilizados` como campo SEPARADO da reconciliação

`integro` continua sendo **só** sobre divergência ledger × projeção.

> **`integro=true` com `potesImobilizados.missoes > 0` é um estado COERENTE, não uma contradição.**

Fazer `integro` virar `false` na presença de pote imobilizado trocaria a única pergunta que aquele
endpoint responde com precisão por uma resposta ruim a duas. `ReconciliacaoService` chama a porta
`missoes/api/DiagnosticoPotes`, cujo `resumir()` é `Propagation.MANDATORY` — ele **recusa** abrir
transação própria, para que o par publicado tenha existido junto em algum instante.

A travessia `carteira → missoes.api` é legítima pelo ArchUnit e não fecha ciclo de bean: a
implementação da porta injeta apenas `MissaoRepository`, e nada em `missoes` injeta
`ReconciliacaoService`. Mesmo arranjo de `ImpactoService`.

### 3. O marco muda por status, e é isso que separa diagnóstico de contador

`ABERTA` mede por `janela_fim`; os demais, por `estado_desde`. É a distinção que `RegraExpiracao
.Marco` já faz, e a razão é a mesma: a janela de oferta é prazo **absoluto** escolhido pelo criador
("preciso disso até sexta"), enquanto abandono é prazo **relativo** ao instante em que a missão parou.

Sem ela, toda missão comunitária financiada com janela longa apareceria como imobilizada assim que
passasse do limiar parada em `ABERTA` — que é o estado **normal** de uma oferta esperando executor.
**Um instrumento cujo falso positivo é o caso comum não é consultado duas vezes.**

Para que as duas listas de status não divirjam em silêncio, `RegraExpiracao.medidosPorJanelaFim()`
passa a ser a fonte única, e `RegraExpiracaoTest.medidosPorJanelaFimAcompanhaOPadrao` a amarra ao que
`padrao(...)` de fato produz. É a mesma classe de acoplamento que quase fez `validarEstado` ficar
fora de sincronia com o construtor de `Missao` quando AJUDA mudou de lado (ADR 0025).

### 4. O limiar, e de onde sai o número

`app.missoes.diagnostico.pote-imobilizado-apos: PT96H`.

**Não é arbitrário:** 96 h é estritamente maior que o maior prazo de varredura (72 h de confirmação)
mais o intervalo do job (5 min). Nada que a varredura trate **corretamente** chega a aparecer aqui.
Quem aparece num estado varrido é sinal de que o job não conseguiu drenar a missão — e
`ExpiracaoMissoesJob` isola falha por item sem derrubar o lote, então esse erro não aparece em lugar
nenhum sozinho.

É namespace novo: não toca `app.missoes.recompensa.*` nem `app.logistica.risco.*`, logo não há
`versao` a subir nem teste dourado envolvido.

### 5. `varreduraCobre`: são dois diagnósticos na mesma lista

- **`true`** — existe varredura para este status e ela **não** drenou a missão. O suspeito é o job.
- **`false`** — varredura nenhuma alcança este status, e a saída depende de um humano específico.

O conjunto vem de `RegraExpiracao.padrao(...)`, montado com os mesmos prazos que o job lê: uma regra
nova passa a ser refletida sem que ninguém edite o serviço de diagnóstico — e, o que importa mais,
sem que ele continue afirmando "não há varredura para este estado" depois que passou a haver.

### 6. Migration V28, porque a medição pediu

Ver a seção **Evidência executada**. O índice parcial `idx_missao_pote_imobilizado` sai de 0,3% do
tamanho da tabela e corta a consulta de 6,2 ms para 0,5 ms. Uma das duas consultas roda em **toda**
chamada da reconciliação, não só no endpoint de diagnóstico — não é custo eventual.

---

## Consequências

**Positivas:**

- A conservação deixa de ser um parágrafo de documentação e vira um número consultável, no lugar
  onde quem desconfia já está olhando.
- `PoteImobilizadoTest.reconciliacaoContinuaIntegraEnquantoODiagnosticoAcusa` afirma as duas
  invariantes na mesma execução. **Enquanto ele passar, ninguém pode argumentar que a reconciliação
  já cobre isto** — e se alguém "consertar" o endpoint fundindo os dois campos, ele fica vermelho.
- A lacuna de `RASCUNHO`/`ACEITA` sai da leitura de código e vira registro executável
  (`estadosSemVarreduraAparecemMarcadosComoTal`).
- Zero caminho de valor novo: nada credita, debita ou muda status. É consulta read-only.
- O ADR 0015 pode finalmente cumprir o que prometeu — por outro caminho, e com consumidor.

**Negativas / trade-offs:**

- **É consulta ATIVA, com o mesmo modo de falha do endpoint de reconciliação: só é olhado por quem
  já desconfia.** Nada avisa que há pote imobilizado. Sem Prometheus e sem Grafana — cortados do MVP
  —, um contador em `/actuator/metrics` seria estado de processo que zera a cada reinício, e diria
  *quantos*, nunca *quais*. É a mesma limitação que o ADR 0031 aceitou para a carta-morta.
- **Três dos seis estados não-terminais não têm porta de ADMIN, então o instrumento mostra dinheiro
  que ninguém ainda consegue soltar.** `DESTRAVAR` só sai de `EM_ANDAMENTO` e `AGUARDANDO_CONFIRMACAO`;
  `EM_DISPUTA` sai por `RESOLVER_*`; `RASCUNHO` e `ACEITA` **não têm nenhuma**. Mostrar mesmo assim é
  deliberado — esconder o que ainda não se sabe consertar repetiria o erro do ADR 0015. Fica
  registrado como pendência nova e nomeada no `CLAUDE.md`.
- **Este ADR não corrige nada.** Ele é detectivo. A mitigação continua sendo preventiva: varredura
  por prazo e porta de ADMIN, ambas do ADR 0015.
- **O predicado está escrito três vezes** (listagem, `countQuery` e agregado) em
  `MissaoRepository`, e divergir seria silencioso: o resumo passaria a contradizer a lista na mesma
  resposta sem nada falhar. A única guarda é `PoteImobilizadoTest.resumoConcordaComALista` — e ela
  foi sabotada para confirmar que tem dentes.
- **O limiar de 96 h é calibração, não verdade.** Erra para o lado longo de propósito, e o preço é
  que uma missão travada por 90 h ainda não aparece.
- **A reconciliação ficou mais cara.** Ganhou uma segunda consulta agregada sobre `missao` — 0,25 ms
  com o índice da V28, contra 5,26 ms sem ele.
- **`IndicePoteImobilizadoTest` explica um SQL escrito nele, não o que o Hibernate emite.** Não há
  constante de SQL para reusar, como `ConsultasGeoespaciaisPostgis.SQL_MISSOES_NO_RAIO` permitiu ao
  teste geoespacial. A corretude da consulta de produção fica com `PoteImobilizadoTest`, que a chama
  pelo endpoint.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Ressuscitar `MissaoRepository.potesImobilizados` | É o erro original, não a solução dele. Aquela query era órfã — escrita, documentada como "visibilidade" e nunca chamada — e ainda **incompleta**: ignorava `RASCUNHO` e `ACEITA`, que retêm pote e não têm varredura nenhuma. Ressuscitá-la sem consumidor faria o ADR 0015 mentir pela segunda vez. |
| Fazer `integro=false` quando há pote imobilizado | Destrói a única pergunta que a reconciliação responde com precisão ("alguma carteira diverge do seu ledger?") para responder mal uma segunda. O projeto já pagou três vezes por confundir as duas invariantes: o estorno na expiração, a cunhagem de ENTREGA e a queima do resgate. |
| Só o número na reconciliação, sem a listagem | Um número não aciona ninguém: `POST /missoes/{id}/destravar` precisa de um id, e não há como descobri-lo. Seria trocar "lacuna declarada" por "alerta sem endereço". |
| Só a listagem, sem o campo na reconciliação | Quem abre a reconciliação não tem motivo para suspeitar que existe um segundo endpoint, e continua lendo `integro=true` como prova de que nada se perdeu — que é precisamente o mal-entendido a desfazer. |
| Um contador em `/actuator/metrics` | Sem Prometheus (cortado do MVP), é estado do processo em memória: **zera a cada reinício** e diz *quantos*, nunca *quais*. Mesmo motivo pelo qual o ADR 0031 recusou o contador para a carta-morta. |
| Alerta automático quando o número passa de zero | O canal seria `alerta`, cujo teto por hora é POR USUÁRIO e não se aplica a sinal de operação — exatamente a condição que produziu **631 linhas idênticas** para o mesmo ponto de custódia em menos de 3 minutos (`f21-carga.md` §6). Repetir o formato antes de resolver aquilo seria escrever a mesma falha duas vezes. |
| Um único limiar medindo todo status por `estado_desde` | Toda missão `ABERTA` financiada com janela longa viraria falso positivo — e ela está no estado NORMAL de uma oferta esperando executor. O falso positivo seria o caso comum. |
| Corte por status derivado de `prazo + folga` em vez de um limiar único | Mais preciso e bem mais caro de explicar: três cortes diferentes numa consulta só. E detectaria antes um job que falhou — que é o caso raro —, ao custo de acusar missão que a varredura ainda vai buscar, que é o caso frequente. |
| Estender `DESTRAVAR` a `RASCUNHO`, `ACEITA` e `EM_DISPUTA` nesta entrega | Tornaria o instrumento acionável em todos os estados que ele acusa, e provavelmente é o próximo passo certo. Mas muda a máquina de estados (17 → 20 transições) e a matriz de `MissaoStateMachineTest`: é uma segunda decisão arquitetural, e embutí-la aqui faria duas decisões viajarem sob um ADR só. |
| Tabela de agregação mantida por trigger ou job | Segunda fonte de verdade para um número que existe justamente para ser conferido. Mesma recusa do ADR 0029 para o painel de impacto — e aqui seria pior, porque a divergência entre a tabela e a realidade é indistinguível do defeito que o número deveria denunciar. |
| Sem migration, aceitando o Seq Scan | Defensável para um endpoint de ADMIN raro — mas o agregado roda em **toda** chamada da reconciliação, que é o endpoint de integridade do sistema. 56 kB de índice parcial contra 16 MB de tabela é preço baixo demais para recusar. |

---

## Evidência executada

### Bancada

50.000 missões sintéticas em `missao`, distribuídas pelos seis estados não-terminais, com `ANALYZE`
antes das medições e as linhas apagadas depois (a tabela voltou às 20 linhas do seed). A forma
importa: **1 em cada 10 segura pote e 1 em cada 100 está parada além do limiar** — 502 candidatas
entre 50.020 linhas. Se a maioria fosse candidata, o Seq Scan seria a escolha CERTA do planner e a
medição estaria descrevendo uma situação que não acontece.

### Antes e depois do índice da V28

```
EXPLAIN (ANALYZE, BUFFERS)              sem índice          com índice        buffers
listagem paginada (LIMIT 20)            6,224 ms            0,481 ms          2008 → 503
contagem + soma (reconciliação)         5,263 ms            0,254 ms          2002 → 503
```

Sem o índice, `Seq Scan on missao` com **49.518 linhas removidas pelo filtro**. Com ele, `Index Scan
using idx_missao_pote_imobilizado`. O índice ocupou **56 kB** contra **16 MB** de tabela — 0,3% —,
porque é parcial: indexa só o que pode ser candidato.

### Uma afirmação escrita e desmentida pela medição

A primeira versão do comentário da V28 dizia que, com dois statuses em `medidosPorJanelaFim()`, o
índice "deixa de ser usado e a consulta volta ao Seq Scan". **Está errado.** O EXPLAIN, na mesma
bancada:

```
status IN ('ABERTA')           — o caso de hoje      Index Scan (Index Cond)     0,324 ms
status IN ('ABERTA','ACEITA')  — hipótese futura     Bitmap Index Scan           1,688 ms
sem este índice                                      Seq Scan                    5,462 ms
```

Com UM status o PostgreSQL normaliza `status IN ('ABERTA')` para `status = 'ABERTA'`, a expressão
casa com a indexada e o marco vira condição de RANGE. Com dois, a expressão deixa de casar — **mas o
índice continua sendo usado pelo predicado parcial**, varrido inteiro (5.004 entradas) e filtrado
depois: ~5× mais lento que hoje, ainda ~3× mais rápido que não ter índice.

O comentário foi corrigido antes do commit. Fica registrado aqui porque o repositório já teve três
comentários falsos achados por auditoria, e o que os produz é exatamente isto: uma afirmação
plausível sobre o planner, escrita sem rodar o `EXPLAIN`.

### A suíte foi sabotada para provar que tem dentes

Quatro sabotagens, uma de cada vez, revertidas em seguida:

| Sabotagem | Resultado |
|---|---|
| `integro` passa a incluir pote imobilizado (as duas invariantes fundidas) | `reconciliacaoContinuaIntegraEnquantoODiagnosticoAcusa` **vermelho** |
| `ABERTA` passa a medir por `estado_desde` (some a distinção de marco) | `abertaComJanelaNoFuturoNaoEhImobilizada` **vermelho** |
| `varreduraCobre` fixado em `true` | `estadosSemVarreduraAparecemMarcadosComoTal` **vermelho** |
| O predicado da contagem perde o corte de idade | **5 testes vermelhos**, entre eles `resumoConcordaComALista` |

Uma quinta tentativa — trocar `pote_tokens > 0` por `> 1` só na contagem — **não** foi detectada, e
pelo motivo certo: todo pote real vale muito mais que 1, então os dois predicados selecionam o mesmo
conjunto. A sabotagem é que era fraca, não o teste; refeita como perda do corte de idade, caiu.

### Build

```
Tests run: 735, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
```

SpotBugs limpo e os dois gates do JaCoCo (80% global, 85% em `dominio`) passando.
