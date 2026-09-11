# 0033 — Deduplicação do alerta operacional: a frequência sai da cópia e volta para a fonte

**Data:** 2026-09-11
**Status:** Aceito

---

## Contexto

O teste de carga de 2026-08-25 ([`docs/evidencias/f21-carga.md`](../evidencias/f21-carga.md) §6)
mediu **631 linhas em `alerta`, todas `PONTO_CUSTODIA_LOTADO`, todas idênticas, todas para o mesmo
ponto, em menos de 3 minutos**. A medição foi pedida sem ajuste, então o achado ficou registrado e
não corrigido — é a última das três armadilhas que a v1.0 abriu por decisão de contrato.

O teto `app.notificacoes.alertas-por-hora` não pega isso, **e está certo em não pegar**. Ele conta
`WHERE usuario_id = ?`, e este alerta tem `usuario_id` nulo de propósito: não é notificação de
ninguém, é sinal de operação. Em SQL `NULL = ?` é UNKNOWN, então a contagem daria zero para sempre.
Não é defeito do teto; é ausência de regra para uma categoria de alerta que o teto nunca modelou.

### O que a leitura do código acrescentou ao enunciado da pendência

Cinco fatos, e cada um mudou alguma coisa na decisão:

1. **Nada na linha identificava o ponto.** `missao_id` é nulo por decisão explícita — o comentário em
   `DespachanteAlertaService` recusa reaproveitá-lo, porque é a coluna por onde o app navega até a
   missão. E `pontoCustodiaId`, que `EntregaFalidaService` publica no payload da outbox desde a V21,
   **nunca era lido**. Deduplicar por `(ponto, janela)` exigia coluna nova; não havia atalho.

2. **O alerta global é write-only. Não há leitor, de nenhum tipo.** Os seis métodos de
   `AlertaRepository` têm `UsuarioId` no predicado, e `usuario_id = <uuid>` nunca casa com `NULL`.
   Não existe endpoint, query ou projeção sobre `usuario_id IS NULL` em todo `src/main/java`. **As
   631 linhas nunca chegaram a ninguém por nenhum caminho do produto.** Isso reenquadra a pendência:
   deduplicar sozinho trocaria 631 linhas não lidas por até 24 por dia não lidas.

3. **A frequência já existia, íntegra, em `entrega_falida`.** Toda recusa grava uma linha com
   `ponto_custodia_id NOT NULL` (V6), `transportadora`, `recusada_em` (V21) e `motivo_recusa` (V23),
   com `ck_entrega_falida_recusa_completa` amarrando os dois últimos. **"LM-ARI-001 recusou 631
   encomendas entre 07:41 e 07:44" era derivável no dia da medição.** As 631 linhas de `alerta` eram
   cópia desnormalizada de um fato já normalizado.

4. **`gravarSemPatrocinio` tinha exatamente a mesma forma e o mesmo defeito**: global, `save`
   incondicional por evento, sem teto e sem dedup. Só não apareceu na medição porque a rajada foi
   contra um ponto cheio, e não contra uma transportadora sem patrocinador.

5. **`gravarPontoLotado` estava em 0/35 instruções de cobertura** (JaCoCo, item 5.4 de
   `docs/auditoria/varredura-orfaos.md`). `WebhookEntregaFalidaTest` chega a apagar
   `PONTO_CUSTODIA_LOTADO` no `@AfterEach` **sem nunca drenar a outbox** — limpava um alerta que não
   chegava a existir. Nenhuma linha daquele caminho era exercitada.

### A pergunta que o contrato mandou responder antes de codar

> *"Qual preserva melhor a intenção do javadoc — deduplicar por `(ponto, janela)`, ou trocar a linha
> por um contador?"*

**Entre as duas isoladas, o contador preserva melhor, e não é empate.** O dado que o javadoc nomeia é
literalmente uma *frequência*: recusas por ponto por tempo. Deduplicar guarda só a existência —
"este ponto esteve cheio nesta hora" — e não distingue 1 recusa de 631. O contador é a dedup **mais**
o número, superset estrito.

**Mas nenhuma das duas é necessária para preservar a frequência**, porque o fato 3 já a preserva. O
contador seria cópia desnormalizada de um agregado derivável — exatamente o que o **ADR 0029**
recusou para o painel de impacto: *"sem migration, sem tabela de agregação e sem cache, porque uma
segunda fonte de verdade para números que existem para serem conferidos é pior que a consulta a
mais"*. E o contador **não elimina a amplificação de escrita** que motivou a pendência: 631 `UPDATE`
na mesma linha geram 631 tuplas mortas por MVCC. O que ele limita é o crescimento em linhas *vivas*,
não o número de escritas.

### As forças em jogo

- **Amplificação de escrita disparada por terceiro.** Uma transportadora em laço de retry contra um
  ponto cheio escreve indefinidamente, e o sistema não controla o gatilho.
- **631 frases iguais não são o dado — são o apagamento dele.** Legibilidade é parte do requisito.
- **Escrita sem leitor é o espelho do erro do ADR 0032.** Lá, uma consulta sem chamador fez a lacuna
  parecer coberta. Aqui, uma escrita sem leitor faz o sinal parecer entregue.
- **Corrigir um gêmeo e deixar o outro reabre a pendência com outro nome.**

---

## Decisão

**Adotamos a deduplicação na escrita e a frequência por CONSULTA — não por contador —, para os DOIS
alertas operacionais globais, com uma migration e um leitor de ADMIN.**

### 1. A chave de deduplicação: `(tipo, referencia, janela_inicio)`

A V29 acrescenta a `alerta` duas colunas nuláveis. `referencia` é **`VARCHAR(100)`, e não `UUID`**,
porque é chave de AGRUPAMENTO e não referência navegável — a distinção é a mesma que mantém
`missao_id` fora deste uso. E `VARCHAR` resolve um problema que `UUID` teria: `MotivoRecusa
.SEM_PATROCINIO` colapsa de propósito três causas, e na primeira delas — patrocinador inexistente —
**não existe `patrocinador_id` para gravar**. Guarda-se o `ponto_custodia_id` em texto num caso e o
slug da transportadora no outro; `tipo` separa os namespaces.

### 2. O índice é PARCIAL em quatro condições, e as duas últimas não são redundantes

```sql
CREATE UNIQUE INDEX uk_alerta_operacional
    ON alerta (tipo, referencia, janela_inicio)
 WHERE usuario_id IS NULL AND referencia IS NOT NULL AND janela_inicio IS NOT NULL;
```

Em PostgreSQL o UNIQUE é `NULLS DISTINCT` por padrão: duas linhas `(tipo, NULL, NULL)` não conflitam.
Sem excluir explicitamente as linhas sem chave, o índice **cobriria as legadas sem deduplicar
nenhuma delas**, e a leitura do `CREATE INDEX` sugeriria uma garantia que ele não dá.

Com o predicado explícito o índice **nasce sobre um banco que já rodou a rajada** — medido, não
suposto: sobre 631 linhas legadas globais sem chave, `CREATE INDEX` sem erro, rajada nova de 631
inserts → `INSERT 0 1`, legadas intactas. A alternativa `NULLS NOT DISTINCT` foi descartada por isso:
faria as legadas colidirem entre si e o `CREATE INDEX` falharia justamente na máquina onde a rajada
foi medida.

### 3. A escrita é `INSERT ... ON CONFLICT DO NOTHING`, e o `WHERE` não pode ser encurtado

A ordem canônica do projeto — *adquira todos os locks → sonde a chave → valide → escreva* — existe
onde há uma linha para travar. **Aqui não há**: o conflito é entre dois INSERTs da MESMA linha
inexistente, e dois drenadores podem estar no mesmo instante, porque `buscarPendentesParaPublicar`
usa `SKIP LOCKED` para que lotes disjuntos corram em paralelo. O `ON CONFLICT` resolve no índice,
dentro do próprio INSERT.

E isso **não** viola a regra vizinha — *"nada captura `DataIntegrityViolationException` para tratar
replay"* — porque nenhuma exceção é lançada: a linha repetida simplesmente não entra.

**O predicado precisa ser repetido palavra por palavra na cláusula de conflito.** Verificado contra o
banco antes de escrever a query, não deduzido da documentação:

```
ON CONFLICT (tipo, referencia, janela_inicio) DO NOTHING
  -> ERROR: there is no unique or exclusion constraint matching the ON CONFLICT specification

ON CONFLICT (tipo, referencia, janela_inicio)
  WHERE usuario_id IS NULL AND referencia IS NOT NULL AND janela_inicio IS NOT NULL
  DO NOTHING                                              -> funciona
```

### 4. A janela é FIXA e ancorada na época, não deslizante

`app.notificacoes.janela-alerta-operacional`, default `PT1H`, truncada em Java por `floorDiv` sobre
`epochSecond`. Janela **deslizante** foi recusada por um motivo concreto: sob rajada contínua ela
empurraria o marco para sempre e a linha nunca seria renovada — o sinal congelaria no primeiro
alerta e *"este ponto ainda está cheio"* deixaria de ser dito. Com janela fixa, cada hora produz no
máximo uma linha e no mínimo nenhuma.

O piso de validação é **um segundo, não "positiva"**, e a diferença é armadilha real: `PT0.5S` é
positiva, sobrevive a um `isZero()/isNegative()`, e `toSeconds()` a trunca para 0 — o `floorDiv`
estouraria com `ArithmeticException`, que subiria pelo despacho, o drenador contaria como falha de
entrega e o evento terminaria na carta-morta. Um erro de configuração vestido de falha de
infraestrutura.

### 5. O corpo do alerta deixou de nomear a transportadora, e a omissão é obrigatória

A linha agora representa a JANELA, não um evento: dentro dela cabem recusas de transportadoras
diferentes. Manter *"recusou uma encomenda de X"* seria **uma afirmação falsa sobre as outras**. O
corpo passou a descrever o estado do ponto, e o teste trava isso (`doesNotContain(SLUG)`).

### 6. `GET /api/v1/admin/pontos-custodia/recusas` — a outra metade, sem a qual isto não fecha

Agrega `entrega_falida` por `(ponto, motivo)` numa janela (padrão 24 h, teto 30 dias), devolvendo
contagem, primeira e última recusa, com `codigo`, `apelido` e `capacidade` do ponto. **Agregado na
hora — sem tabela de agregação e sem cache** (ADR 0029). Só ADMIN: descreve a operação de um parceiro
comercial, quais lojas do bairro estão sem espaço e com que frequência.

A V29 leva o índice que torna isso barato. `idx_entrega_falida_ponto` (V21) é parcial em
`recusada_em IS NULL` — **o complemento exato desta consulta**, portanto inútil para ela.

### 7. Sem migration de dado, e sem apagar as linhas legadas

A V29 não toca nas 631 linhas de uma máquina que rodou a carga. Apagá-las seria perda de dado
decidida por quem escreve a migration, não pelo dono do sistema; e o índice parcial as tolera por
construção.

---

## Consequências

**Positivas:**

- **A rajada de §6 deixou de escrever sem limite.** Contra o servidor de pé: 60 webhooks num ponto
  cheio → **1 linha** em `alerta`, com as **60 recusas** intactas em `entrega_falida`.
- **A frequência ficou legível pela primeira vez.** Antes existia implícita e sem leitor; agora
  `recusas: 60` com primeira e última recusa, num endpoint.
- **O caminho saiu de 0/35 instruções para coberto.** `DespachanteAlertaOperacionalTest` é a primeira
  cobertura que `gravarPontoLotado` recebe, e a suíte inteira subiu de 735 para **744** testes.
- **O gêmeo foi fechado junto.** `ENTREGA_SEM_PATROCINIO` deduplica pela transportadora, e
  `semPatrocinioDeduplicaPelaTransportadora` reprova o build se alguém reverter só ele.
- **Duas afirmações falsas foram corrigidas onde estavam.** O javadoc de `Alerta` dizia *"nenhum
  caminho de escrita produz [alerta global] hoje"* — dois produzem. O de `AlertaRepository` dizia
  *"não há UNIQUE na tabela"* — agora há um, parcial, e a frase explica por que ele não alcança a
  caixa de entrada.

**Negativas / trade-offs:**

- **O leitor é consulta ATIVA, com o mesmo modo de falha da carta-morta (0031) e do pote imobilizado
  (0032): nada avisa. Alguém precisa olhar.** É a terceira vez que este projeto entrega um
  instrumento passivo, e vale dizer em voz alta que três instrumentos passivos não somam um alarme.
- **A granularidade do sinal caiu de propósito.** Um ponto que recusa uma vez por hora e um que
  recusa 600 produzem a mesma linha de alerta. Quem quiser distinguir tem de consultar o painel — a
  informação existe, mas deixou de estar no lugar onde alguém tropeçaria nela.
- **Um ponto cronicamente cheio ainda gera até 24 linhas por dia.** É bounded, não silencioso.
- **A dedup depende do código preencher `referencia`, e o banco NÃO obriga.** Um caminho de escrita
  novo que grave alerta global sem chave fica FORA do predicado parcial e não é deduplicado — sem
  erro e sem aviso. Quem cobre isso é o teste, não uma constraint. Um `CHECK` exigindo as colunas
  reprovaria as linhas legadas, que não têm como preenchê-las sem inventar dado.
- **Não há recorte por transportadora no painel.** Para `PONTO_LOTADO` é intencional — a pergunta é
  sobre capacidade do ponto. Para `SEM_PATROCINIO` a chave acionável *é* a transportadora, e ela fica
  legível só no corpo do próprio alerta. A contagem por transportadora é derivável e ninguém a expõe.
- **Duas colunas da tabela não são mapeadas pela entidade.** É deliberado e documentado, mas é uma
  assimetria que a próxima pessoa vai querer "consertar".

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|---|---|
| **Contador `ocorrencias` na linha do alerta**, incrementado por upsert | É a que preserva melhor a intenção do javadoc **entre as duas propostas isoladas**, e mesmo assim perde: seria segunda fonte de verdade para um agregado que `entrega_falida` já guarda exato — o que o ADR 0029 recusou para o painel de impacto. E não resolve a amplificação de escrita que motivou a pendência: 631 `UPDATE` na mesma linha são 631 tuplas mortas por MVCC; o que cai é o número de linhas *vivas*, não o de escritas. |
| **Deduplicar e parar por aí, sem leitor** | Trocaria 631 linhas que ninguém lê por até 24 por dia que ninguém lê, e apagaria a contagem sem devolvê-la em lugar nenhum. É o erro do ADR 0032 de cabeça para baixo: lá a consulta não tinha chamador; aqui a escrita não tem leitor. |
| **Parar de escrever o alerta e expor só a consulta** | Zero amplificação e zero perda de informação, já que o fato é gravado em `entrega_falida` de qualquer forma. Recusada porque apagaria a noção de *sinal de operação* do módulo `notificacoes` e deixaria o sistema sem nenhum ponto onde "algo está errado na operação" seja afirmado — só derivável por quem souber montar a consulta. |
| **Aplicar o teto `alertas-por-hora` ao alerta global** | Não funciona e o motivo é estrutural, não de calibração: o teto conta `WHERE usuario_id = ?` e estes alertas têm `usuario_id` nulo. `NULL = ?` é UNKNOWN e a contagem daria zero para sempre. Precisaria de uma segunda contagem com semântica diferente — que é a dedup, por outro nome e sem chave. |
| **`NULLS NOT DISTINCT` no índice único** (PostgreSQL 15+) | Tornaria um `referencia` nulo um erro duro em vez de uma dedup silenciosamente desligada, o que é preferível. Mas faria as linhas legadas colidirem entre si e o `CREATE INDEX` **falharia exatamente na máquina onde a rajada de 631 foi medida** — a migration quebraria no banco que mais precisa dela. |
| **UNIQUE total, sem predicado parcial** | Funciona: as três colunas são nulas nos alertas de usuário e `NULL` não conflita em UNIQUE. Recusada porque um índice cujo predicado não diz o que ele cobre convida à leitura errada — o `CREATE INDEX` pareceria restringir a caixa de entrada por usuário, que ele não toca. |
| **`referencia` como `UUID`** | Mais forte por tipo, e impossível: `SEM_PATROCINIO` colapsa três causas de propósito e na primeira não existe patrocinador para referenciar. Exigiria um UUID derivado do slug por hash — identificador inventado, ilegível em suporte, para uma coluna cujo único papel é agrupar. |
| **Tabela de agregação `recusa_por_ponto_janela`** | Pior versão do contador: além da segunda fonte de verdade, precisaria de manutenção transacional junto de `entrega_falida` e de reconciliação própria. A medição mostrou que a consulta direta custa 0,135 ms em 50.000 linhas. |
| **Sondagem `exists` antes do insert, em Java** | É o padrão do fan-out por usuário (`existsByUsuarioIdAndTipoAndMissaoId`) e aqui abriria corrida: sem linha para travar, dois drenadores concorrentes leem "não existe" ao mesmo tempo e ambos inserem. O `ON CONFLICT` fecha no índice, que é o único ponto de serialização disponível. |
| **Janela deslizante a partir do último alerta** | Sob rajada contínua o marco seria empurrado para sempre e a linha nunca se renovaria: o sinal congelaria no primeiro alerta e "este ponto ainda está cheio" deixaria de ser dito justamente enquanto continua verdade. |
| **Encurtar a janela no perfil de teste (`PT1S`)** | Tentador para o teste atravessar a janela sem dormir. Faria a rajada de 50 webhooks **flakar sempre que cruzasse a borda do segundo**, gravando 2 linhas onde o teste espera 1, sem nenhuma mudança de código. O teste atravessa a janela envelhecendo `janela_inicio` no banco, e `application-test.yml` mantém `PT1H` igual à produção. |

---

## Evidência executada

### O `ON CONFLICT` e o índice parcial, verificados no banco antes de escrever a migration

```
-- sem repetir o predicado:
ERROR:  there is no unique or exclusion constraint matching the ON CONFLICT specification

-- com o predicado, sobre 631 linhas legadas globais sem chave de dedup:
CREATE INDEX                       -- o índice nasce; as legadas ficam fora por predicado
INSERT 0 1                         -- rajada nova de 631 na mesma janela
PONTO_CUSTODIA_LOTADO | p1   |   1
PONTO_CUSTODIA_LOTADO | (null) | 631   -- legadas intactas
```

### Ciclo ponta a ponta contra o servidor de pé

60 webhooks contra `PT-PIN-904` (capacidade 2 / ocupação 2), todos HTTP 200:

```
alerta (usuario_id IS NULL):
 tipo                  | referencia                           | janela_inicio          | linhas
 PONTO_CUSTODIA_LOTADO | cccccccc-0000-0000-0000-000000000904 | 2026-09-11 16:00:00+00 |      1

entrega_falida:
 motivo_recusa | recusas | primeira                      | ultima
 PONTO_LOTADO  |      60 | 2026-09-11 16:46:16.438066+00 | 2026-09-11 16:46:18.535482+00

outbox: 60 eventos, 0 pendentes, MAX(tentativas) = 0
```

```json
GET /api/v1/admin/pontos-custodia/recusas   (ADMIN)
{"pontoCustodiaId":"cccccccc-0000-0000-0000-000000000904","codigo":"PT-PIN-904",
 "apelido":"Portaria Ed. Aurora (lotada)","capacidade":2,"motivo":"PONTO_LOTADO","recusas":60,
 "primeiraRecusa":"2026-09-11T16:46:16.438066Z","ultimaRecusa":"2026-09-11T16:46:18.535482Z"}
```

O mesmo endpoint como `alice` (USUARIO): **HTTP 403**.

### Antes e depois do índice da V29

Consulta REAL do endpoint, schema real, 50.000 linhas (10% recusadas, 204 na janela de 24 h),
`ANALYZE` antes de cada plano, tudo dentro de uma transação revertida:

| | sem o índice | com `idx_entrega_falida_recusa` |
|---|---|---|
| plano | Seq Scan + Merge Join + Incremental Sort | **Index Scan** + Hash Join + HashAggregate |
| linhas descartadas no filtro | 49.878 | 0 |
| buffers | 881 | **32** |
| tempo | 3,082 ms | **0,135 ms** |

Índice de **128 kB** contra 14 MB de tabela — 0,9% —, por ser parcial. Para comparação,
`idx_entrega_falida_ponto` (V21) ocupa 600 kB na mesma bancada.

### A suíte foi sabotada para provar que tem dentes

| Sabotagem | Resultado |
|---|---|
| Tirar o predicado da cláusula `ON CONFLICT` | **8 vermelhos** (1 falha + 7 erros) |
| Deduplicar só por `tipo` (`referencia` constante) | **2 vermelhos** |
| Congelar `janelaDe()` numa constante | **1 vermelho** — *só depois de corrigir a asserção* |
| `SEM_PATROCINIO` volta ao `save` incondicional | **1 vermelho** |
| Inverter o `ORDER BY` do painel | **não detectada** |

**A terceira sabotagem PASSOU na primeira tentativa, e é o achado desta passada.** O teste da janela
seguinte funciona envelhecendo `janela_inicio` no banco — então ele passaria com uma `janelaDe()`
que devolvesse sempre a mesma constante, porque o que ele mede é "uma janela diferente não bloqueia",
e não "a janela vem do relógio". A correção foi comparar `janela_inicio` com o `criado_em` da própria
linha (`truncatedTo(HOURS)`), o que é determinístico: os dois saem do mesmo `agora`, então nem uma
execução que cruze a virada da hora produz divergência. **Um teste que envelhece o estado que ele
deveria estar derivando é cego para a derivação** — vale para qualquer janela ou marco temporal deste
repositório.

A quinta não foi detectada, e pelo motivo certo: o teste cria recusas de um único ponto e motivo,
então há **um grupo só** e qualquer ordenação produz a mesma página. Ordenar é comportamento do
painel que a suíte não exercita.

### Build

```
Tests run: 744, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
```

SpotBugs com 0 bugs e os dois gates do JaCoCo passando (*"All coverage checks have been met"* nas
execuções `check-global` e `check-dominio`).
