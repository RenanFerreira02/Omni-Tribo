# Auditoria final de entrega — Omni-Tribo

**Data:** 2026-09-12 · **Branch:** `develop` · **HEAD:** `c5a8426`
**Especificação-alvo:** enunciado da Atividade 4 — *"O produto precisa estar concluído dentro do
escopo definido pelo grupo, organizado, testado, demonstrável, e acompanhado das informações
necessárias para que outra pessoa compreenda o que foi construído. Aquilo que o grupo promete
entregar precisa estar efetivamente funcionando."*

---

## Método — o que foi EXECUTADO

Ambiente recebido pronto e **não** recriado: `make demo` às 22:42, volume destruído e reconstruído,
backend `dev` de pé desde 22:43 na 8080 (actuator 8090). Nenhum `./mvnw`, nenhum `make reset`,
nenhum subagente.

| # | Comando executado | Para quê |
|---|---|---|
| 1 | `curl -s http://localhost:8080/v3/api-docs` | inventário real de 59 operações |
| 2 | `grep -E '@(Get\|Post\|Put\|Patch\|Delete)Mapping' -r services/api/src/main/java \| wc -l` → **59** | confronto código × Swagger |
| 3 | `curl` em 23 rotas com **três identidades** (anônimo, `renan` USUARIO, `admin` ADMIN) | status e papel reais |
| 4 | `psql` (via `tools/demo/compose.sh exec -T db`) — snapshots de `carteira`/`missao`/`lancamento` | invariante econômica independente do script |
| 5 | Bloco `1:00–2:00` do `ROTEIRO-DEMO.md`: aporte + **replay com a mesma chave** | idempotência da emissão |
| 6 | Bloco `2:00–4:00`: `tools/carrier-mock/enviar.sh` completo | 12 cenários do webhook |
| 7 | Bloco `4:00–5:00`: catálogo de benefícios + resgate + **replay** | sumidouro do token |
| 8 | `bash tools/evidencias/conservacao-por-categoria.sh` | 4 ciclos + 1 sem patrocínio |
| 9 | `GET /admin/carteiras/reconciliacao` em **4 pontos** da sessão | `integro` e `potesImobilizados` |
| 10 | Contagens em disco: ADRs, auditorias, migrations, classes de teste, suítes Jest, telas | item 6 |

---

## Veredito

| Item | Assunto | Classificação |
|---|---|---|
| 1 | Toda funcionalidade prometida existe e responde | **CONFORME** (59/59 operações) — com **1 LACUNA** (README sem inventário de rotas, `README.md:159,356`) e **1 DIVERGÊNCIA** de notação (`CLAUDE.md`, consentimentos) |
| 2 | Roteiro roda inteiro em banco recém-resetado | **CONFORME** nos 5 primeiros minutos — com **1 DEFEITO** de documento (`ROTEIRO-DEMO.md:181` — saldo pós-resgate aritmeticamente impossível) e **1 DIVERGÊNCIA ACEITÁVEL** (65→64, justificada em `ROTEIRO-DEMO.md:142-148`) |
| 3 | A invariante econômica fecha | **CONFORME** — Δ=0 nos cinco ciclos, ±500/−15 nas duas pontas, medido por `psql` independente do script |
| 4 | Código órfão | **CONFORME** — 0 de 19 classes novas com método sem execução; 4 dos 6 candidatos REFUTADOS. Com **1 DEFEITO** latente (prefixo sentinela colidindo, `IndicePoteImobilizadoTest.java:48` × `PlanoConsultasQuentesTest.java:54`) e **1 LACUNA** (8 defaults de paginação, 5 ramos nunca executados) |
| 5 | Comentário afirmando garantia inexistente | **1 DEFEITO** (`V29__dedup_alerta_operacional.sql:62` nomeia classe inexistente) · **1 EXCEDENTE** (`AlertaRepository.java:88-92`) · restante **CONFORME**, verificado por execução |
| 6 | Divergência de documentação nova | **7 DEFEITOS · 3 LACUNAS · 3 DIVERGÊNCIAS ACEITÁVEIS** — incluindo **2 achados novos**: F16/F17 designam duas fases cada, e F8 marcada "Parcial" |

### Resumo numérico

| Classe | Quantos | Os mais graves |
|---|---|---|
| **DEFEITO** | 11 | `README.md:5,7,13,15` (5 imagens ausentes + placeholder de vídeo) · `README.md:74,78,79` (citação que não sustenta o número) · `ROTEIRO-DEMO.md:181` (175 aritmeticamente impossível) · `V29…sql:62` (classe fantasma) · `IndicePoteImobilizadoTest.java:48` (prefixo colidindo) · `apps/mobile/CLAUDE.md:146` + `PROGRESSO.md:480` (F16/F17 ambíguos) |
| **LACUNA** | 5 | `README.md` sem inventário de rotas · `PROGRESSO.md:3-21` sem F14–F17 · 8 defaults de paginação sem teste |
| **DIVERGÊNCIA ACEITÁVEL** | 5 | 65→64 do roteiro (justificada em `ROTEIRO-DEMO.md:142-148`) · `DIVERGENCIAS-DOCUMENTACAO.md:99` (documento datado) |
| **EXCEDENTE** | 1 | `AlertaRepository.java:88-92` prevê a mensagem de erro exata do atalho plausível |
| **CONFORME** | — | 59/59 operações respondem com o papel correto · invariante econômica nas duas pontas · 19/19 classes novas executadas · 4 de 6 candidatos a órfão refutados |

**Veredito de uma linha.** O produto **funciona** e a parte difícil está certa: a economia fecha nas
duas pontas, os 12 cenários do webhook passam, o ciclo completo credita o executor, e não há código
órfão. **Nenhum defeito de código foi encontrado nesta auditoria.** O que está errado é o que
*descreve* o produto — README, tabela de fases e dois ponteiros de comentário. Para a cláusula
*"acompanhado das informações necessárias para que outra pessoa compreenda o que foi construído"*,
isso é exatamente o lugar errado para estar errado.

---

## Item 1 — Toda funcionalidade prometida existe e responde?

### 1.1 O inventário bate: 59 = 59 = 21 controllers — CONFORME

```
$ curl -s http://localhost:8080/v3/api-docs | python3 -c "...contagem de operações..."
TOTAL 59

$ grep -rn "@(Get|Post|Put|Patch|Delete)Mapping" --include=*.java services/api/src/main/java | wc -l
59

$ grep -rln "RestController" --include=*.java services/api/src/main/java | wc -l
22          # 21 controllers + GlobalExceptionHandler (@RestControllerAdvice)
```

As 59 operações do Swagger foram conferidas uma a uma contra a seção *"Superfície de API hoje"* do
`CLAUDE.md` da raiz. **Nenhuma rota no Swagger está ausente da documentação; nenhuma rota
documentada está ausente do Swagger.** O único desencontro é de notação, tratado em 1.4.

### 1.2 Papel exigido: as seis rotas ADMIN de leitura reprovam USUARIO — CONFORME

Token de `renan@omnitribo.dev` (USUARIO) contra token de `admin@omnitribo.dev` (ADMIN), mais anônimo:

```
/api/v1/admin/carteiras/reconciliacao              usu=403 adm=200 anon=401
/api/v1/admin/impacto                              usu=403 adm=200 anon=401
/api/v1/admin/missoes/potes-imobilizados           usu=403 adm=200 anon=401
/api/v1/admin/outbox/esgotados                     usu=403 adm=200 anon=401
/api/v1/admin/patrocinadores                       usu=403 adm=200 anon=401
/api/v1/admin/pontos-custodia/recusas              usu=403 adm=200 anon=401
```

E nas de escrita:

```
POST   /api/v1/admin/patrocinadores/{id}/aportes  (USUARIO)  -> 403
PATCH  /api/v1/admin/resgates/{id}                (USUARIO)  -> 403
DELETE /api/v1/admin/patrocinadores/{id}          (USUARIO)  -> 403
POST   /api/v1/admin/beneficios  corpo válido     (USUARIO)  -> 403   (nada gravado: count=0)
```

### 1.3 Rotas de usuário: anônimo é 401, autenticado é 200 — CONFORME

```
/api/v1/ping                                                   anon=200 usu=200
/api/v1/missoes                                                anon=401 usu=200
/api/v1/missoes/proximas?lat&lon&raioMetros                    anon=401 usu=200
/api/v1/carteira | /carteira/lancamentos                       anon=401 usu=200
/api/v1/alertas | /alertas/nao-lidos/contagem                  anon=401 usu=200
/api/v1/tribos | /auth/me | /usuarios/me | /me/dados           anon=401 usu=200
/api/v1/usuarios/me/consentimentos                             anon=401 usu=200
/api/v1/usuarios/busca?handle=alice                            anon=401 usu=404
/api/v1/pontos-custodia?lat&lon&raioMetros                     anon=401 usu=200
/api/v1/beneficios?triboId=…901                                anon=401 usu=200
/api/v1/clima?lat&lon | /enderecos/01310100                    anon=401 usu=200
```

O `404` da busca de handle **é o comportamento documentado, não defeito**: `renan` é da Tribo Cidade
Líder e `alice` é de Pinheiros, e o `CLAUDE.md` diz que "inexistente, de outra tribo e conta inativa
respondem o MESMO 404, indistinguíveis". Medido: a busca respondeu 404 para um handle real de outra
tribo, ou seja, a indistinguibilidade é real e não só declarada.

Contratos de escrita conferidos contra o que a documentação promete:

```
POST /carteira/transferencias  SEM Idempotency-Key            -> 400   (header exigido, como documentado)
POST /carteira/saques          (app.carteira.saque-habilitado=false) -> 400
POST /missoes  com "valorBrl": 10                             -> 400   ("nenhuma missão pode ter valor_brl > 0")
DELETE /usuarios/me sem a senha no corpo                      -> 400   (senha atual exigida)
POST /admin/outbox/{uuid-inexistente}/reenfileirar (ADMIN)    -> 404
```

O DTO de criação de missão, lido do próprio `/v3/api-docs`, **não tem `xpRecompensa` nem
`tokensRecompensa`** — a regra "quem cria a missão não define a recompensa" é estrutural, não só
declarada:

```
CriarMissaoRequest props: ['categoria','titulo','descricao','valorBrl','complexidade',
 'origemLat','origemLon','destinoLat','destinoLon','cep','logradouro','bairro','cidade','uf',
 'raioCheckinM','pesoKg','volumeL','janelaInicio','janelaFim','pontoCustodiaId']
```

### 1.4 DIVERGÊNCIA (menor) — a notação de consentimentos promete um GET que não existe

`CLAUDE.md` (raiz), seção *Superfície de API hoje*:
`GET|PUT me/consentimentos[/{tipo}]`. Lida ao pé da letra, a notação distribui os dois verbos sobre
as duas formas — inclusive `GET /me/consentimentos/{tipo}`. Medido:

```
GET /api/v1/usuarios/me/consentimentos/NOTIFICACAO  ->  405
```

O Swagger só expõe `GET /me/consentimentos` e `PUT /me/consentimentos/{tipo}`. **Não é defeito de
código** — o `GET` de coleção resolve o caso de uso —, é ambiguidade da notação. Classificação:
**DIVERGÊNCIA ACEITÁVEL** se a intenção era `GET` coleção + `PUT` item; a correção é trocar por
`GET me/consentimentos · PUT me/consentimentos/{tipo}`, sem colchete.

### 1.5 LACUNA — o README não enumera nenhuma rota

`README.md` tem **duas** ocorrências da string `api/v1` no arquivo inteiro:

```
$ grep -c "api/v1" README.md
2
159:`GET /api/v1/admin/missoes/potes-imobilizados` mostra o token preso em missão parada, e a
356:curl http://localhost:8080/api/v1/ping
```

O enunciado pede *"acompanhado das informações necessárias para que outra pessoa compreenda o que foi
construído"*. Hoje, quem chega pelo README **não descobre que existem 59 operações**: a superfície só
está enumerada no `CLAUDE.md` — arquivo de instrução para agente, não documento de entrega — e no
Swagger, que exige o backend de pé. O ponteiro para o Swagger existe (`README.md:356` mostra o `ping`
e a seção de execução cita `swagger-ui.html`), mas nenhum documento estático lista as rotas.

Consequência prática: um avaliador que leia o repositório sem executá-lo não tem como julgar se "o
que o grupo promete entregar está funcionando", porque a promessa não está escrita em lugar
navegável. **LACUNA.** Custo de correção: uma tabela de 59 linhas gerável do `/v3/api-docs`.

### 1.6 Não-achado que checei e descartei

Corpo malformado em rota ADMIN devolve **400 antes do 403** — a desserialização falha antes da
autorização:

```
POST /api/v1/admin/beneficios  corpo '{}'   USUARIO -> 400 "Failed to read request"
POST /api/v1/admin/beneficios  corpo '{}'   ADMIN   -> 400 "Failed to read request"
POST /api/v1/admin/beneficios  corpo válido USUARIO -> 403
```

Isso permite a um USUARIO distinguir "corpo malformado" de "sem permissão" numa rota ADMIN.
**Não classifico como defeito**: o schema já é público em `/v3/api-docs` (respondeu 200 anônimo), o
oráculo não revela nada além do schema, e nenhum estado muda (`count(*) FROM beneficio WHERE
titulo='Auditoria teste'` = 0). Registro por transparência.

---

## Item 2 — O roteiro roda inteiro num banco recém-resetado?

### 2.1 Os cinco IDs fixos do roteiro existem no seed — CONFORME

```
 patrocinador | 77777777-0000-0000-0000-000000000950 | ativo=true
 ponto_custodia | cccccccc-…0902 | LOCKER Cidade Líder | cap 24 | ocup 3 | ativo t
 tribo | aaaaaaaa-…0901 | Tribo Cidade Líder
 beneficio | 33333333-…0960 | Um café coado e um pão na chapa | 15 | ativo t
 usuario | bbbbbbbb-…0901 | renan@omnitribo.dev | saldo 124
```

O saldo inicial de `renan` é **exatamente 124**, o número que o roteiro cola em `ROTEIRO-DEMO.md:132`.

### 2.2 `0:00–1:00` (fala) — CONFORME

A narrativa afirma três coisas verificáveis, e as três se sustentam no que o sistema faz:
(a) a entrega falida vira missão remunerada — comprovado no bloco 2:00–4:00;
(b) `CONCLUIDA` é o único estado que credita — o script confere crédito só após `CONCLUIDA`;
(c) o custo do fracasso vira renda comunitária — o pote de `PATROCINADOR` sai da carteira da
transportadora (medido: 5372 → 5306 no ciclo 4 do item 3). Nada na fala promete algo ausente.

### 2.3 `1:00–2:00` — o aporte e o replay — CONFORME, número idêntico ao colado

```
$ curl -X POST …/admin/patrocinadores/77777777-…0950/aportes -H 'Idempotency-Key: auditoria-aporte-1' -d '{"tokens":500}'
{"patrocinadorId":"77777777-0000-0000-0000-000000000950","usuarioId":"bbbbbbbb-0000-0000-0000-000000000950",
 "lancamentoId":"57060f13-10df-4b38-b662-b396b28f999b","saldoTokens":5500,"replay":false}

$ (mesma chave, de novo)
{"…","lancamentoId":"57060f13-10df-4b38-b662-b396b28f999b","saldoTokens":5500,"replay":true}
```

`saldoTokens: 5500` e `replay: false → true` batem com `ROTEIRO-DEMO.md:105`. **O replay é real, não
declarado**: mesmo `lancamentoId`, e a soma global subiu 500 uma única vez (10845 → 11345, §3.2).

Nota mínima: o JSON colado no roteiro **omite o campo `usuarioId`**, que a resposta real traz. É
diferença de recorte de exemplo, não de contrato — não classifico.

### 2.4 `2:00–4:00` — `enviar.sh`: 12 cenários, 12 verdes

```
  OK   caminho feliz → vira missão              HTTP 200   desfecho CONVERTIDA
  OK   replay → mesma missão, sem duplicar      HTTP 200   replay:true, mesmo missaoId
  OK   assinatura inválida → 401                HTTP 401
  OK   timestamp de 10 min atrás → 401          HTTP 401
  OK   ponto lotado → 200 RECUSADA, sem missão  HTTP 200   missaoId:null
  OK   ponto inexistente → 404                  HTTP 404
── Ciclo completo ──
  OK   reporte da falha → vira missão           HTTP 200
        executor: renan@omnitribo.dev  saldo ANTES: 124 tokens
  ..  aceitar / iniciar / check-in    ACEITA → EM_ANDAMENTO → AGUARDANDO_CONFIRMACAO
  OK   confirmação → executor creditado         HTTP 200   tokensCreditados: 64
        saldo DEPOIS: 188 tokens  (creditados: 64)
  OK   saldo subiu exatamente a recompensa: +64
  OK   replay da confirmação → no-op            HTTP 200   tokensCreditados: 0
  OK   saldo intacto no replay: 188
  OK   confirmação com assinatura inválida → 401
  OK   confirmação de rastreio desconhecido → 404
  OK   confirmação sem check-in → 409
Todos os cenários responderam como esperado.
```

**Contagem:** doze chamadas verificadas — exatamente os "doze cenários" de `ROTEIRO-DEMO.md:117`.
**CONFORME.**

**Saldo ANTES: 124 — idêntico ao colado. CONFORME.**

**Saldo DEPOIS: 188, creditados 64 — o roteiro cola 189 e 65.** Causa medida, no banco:

```
 id=c6c6a899-… | CONCLUIDA | ENTREGA | PATROCINADOR | xp 192 | tokens 64 | pote 0
 multiplicador_risco = 1.05 | versao_formula = 3
```

O multiplicador congelado foi **1,05×**, e não o 1,06× de 2026-09-12 que o roteiro registra. O
insumo é o clima consultado ao vivo. **DIVERGÊNCIA ACEITÁVEL**, e a justificativa está no próprio
documento, `ROTEIRO-DEMO.md:142-148`: *"Leia o `+N` que o script imprimir; não decore este número…
uma diferença no valor absoluto, não [acusa erro]"*. O script confere o crédito contra a recompensa
**da própria missão**, então a assertion continua sendo real — ela passou com 64.

### 2.5 `4:00–5:00` — catálogo idêntico; resgate correto; **o roteiro tem um número impossível**

Catálogo — cinco itens, 10 a 40 tokens, **linha por linha igual ao colado em `ROTEIRO-DEMO.md:164-170`**:

```
10 tokens · Um remendo de câmara de ar (Bicicletaria do Zé)
15 tokens · Um café coado e um pão na chapa (Padaria Pão da Praça)
25 tokens · Uma fornada de pão francês (500 g) (Padaria Pão da Praça)
30 tokens · 15% de desconto na feira da semana (Mercearia Dona Neusa)
40 tokens · 20% de desconto na revisão da bicicleta (Bicicletaria do Zé)
```

Resgate:

```
{"id":"df8d2d03-…","beneficioId":"33333333-…0960","custoTokens":15,"codigoRetirada":"TC955KEJ",
 "status":"PENDENTE","saldoTokensRestante":173,"replay":false}
{"…","codigoRetirada":"TC955KEJ","saldoTokensRestante":173,"replay":true}     # replay real
```

`custoTokens: 15` ✓. `codigoRetirada` diferente — aleatório, **não é achado**, como o escopo já diz.

#### DEFEITO — `ROTEIRO-DEMO.md:181`: `"saldoTokensRestante": 175` não fecha com o próprio roteiro

O roteiro cola, no bloco anterior, `saldo DEPOIS: 189 tokens`. E cola, neste bloco, `custoTokens: 15`
com `saldoTokensRestante: 175`. **189 − 15 = 174, não 175.** Os dois blocos colados não podem ter
saído da mesma execução, e nenhum passo entre eles credita nada.

A medição desta auditoria fecha: 188 − 15 = **173**, e foi o que a API devolveu.

Isto **não** é coberto pela ressalva de `ROTEIRO-DEMO.md:142-148`, que perdoa a variação do
multiplicador — a ressalva explica por que 189 pode virar 188, mas não por que 189 vira 175 depois de
um débito de 15. É inconsistência aritmética interna do documento.

Consequência prática: o roteiro é lido em voz alta na frente de uma banca. Quem confere a subtração
enquanto o apresentador fala vê um número que não fecha, exatamente no bloco em que o argumento é
*"aqui o token é queimado"*. Custa mais credibilidade do que a diferença de 2 tokens sugere.
**DEFEITO** (de documento, não de código; o código está certo).

### 2.6 Blocos não verificados, por decisão do autor do escopo

| Bloco | Assunto | Estado |
|---|---|---|
| `5:00–6:00` | resiliência / rede externa (ViaCEP, Open-Meteo) | **NÃO VERIFICADO** — fora do escopo por decisão do autor |
| `6:00–8:00` | economia | **parcialmente verificado** — o `conservacao-por-categoria.sh` que ele roda é o item 3 desta auditoria, e passou |
| `8:00–9:00` | — | **NÃO VERIFICADO** — fora do escopo por decisão do autor |
| `9:00–10:00` | — | **NÃO VERIFICADO** — fora do escopo por decisão do autor |

Observação lateral, medida sem custo: as duas rotas do bloco 5 responderam **200** com a rede desta
máquina (`/api/v1/enderecos/01310100` e `/api/v1/clima?lat&lon`), ou seja, o plano A do bloco 5
funciona hoje. O plano B (503 com `servico-externo-indisponivel`) não foi exercitado.

---

## Item 3 — A invariante econômica fecha?

### 3.1 O script mede a fórmula certa — auditoria do `conservacao-por-categoria.sh`

`tools/evidencias/conservacao-por-categoria.sh:47-50`:

```sql
SELECT (SELECT COALESCE(SUM(saldo_tokens),0) FROM carteira) || '|' ||
       (SELECT COALESCE(SUM(pote_tokens),0)  FROM missao)   || '|' ||
       (SELECT COALESCE(SUM(saldo_tokens),0) FROM carteira)
     + (SELECT COALESCE(SUM(pote_tokens),0)  FROM missao);
```

É **literalmente** `SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)`, o enunciado do `CLAUDE.md`
e do ADR 0027. Sem filtro de status, sem filtro de categoria, sem recorte temporal — não há como o
script "não ver" um pote preso ou uma carteira nova. **CONFORME.**

Os ciclos **fecham de verdade**: cada um dos quatro chega a `CONCLUIDA` e o executor é creditado —
não é o caso patológico de "script que mede conservação sem mover token". Trecho literal do ciclo 4,
o único que envolve terceiro pagador:

```
saldo do patrocinador antes: 5372
missão: fonte_pote|recompensa|pote = PATROCINADOR|66|66
    ✓ pote cobre a recompensa: 66
aceitar: ACEITA / iniciar: EM_ANDAMENTO / checkin: AGUARDANDO_CONFIRMACAO
confirmação: {"tokensCreditados":66,"replay":false}
    ✓ conclusão por confirmação da transportadora: CONCLUIDA
saldo do patrocinador depois: 5306  (pagou 66)
```

O quinto ciclo é o negativo, e também assere estado, não só HTTP:

```
webhook: {"desfecho":"SEM_PATROCINIO","missaoId":null,…}
    ✓ nenhuma missão criada: 26
    ✓ nenhum token cunhado: 11330
```

Saída final do script:

```
############ RESUMO ############
TRIBO    Δ=0  recompensa=38
COLETA   Δ=0  recompensa=35
AJUDA    Δ=0  recompensa=30
ENTREGA  Δ=0  recompensa=66  (pote pago pelo patrocinador)
conservação: baseline=11330  final=11330
reconciliação final: {"integro":true,"divergencias":0}
lançamentos por motivo:
APORTE_PATROCINADOR = 3
BONUS = 4
FINANCIAMENTO_PATROCINADOR = 3
FINANCIAMENTO_TRIBO = 7
RECOMPENSA_MISSAO = 13
RESGATE = 1
missões por fonte_pote:
COMUNIDADE = 13
CUNHAGEM = 10
PATROCINADOR = 3

Todas as conferências passaram.
```

### 3.2 A conta independente, por `psql` — as DUAS partes da invariante, separadas

Cada linha abaixo é um `SELECT` meu, rodado direto no contêiner, **sem passar pelo script**:

| Momento | `SUM(saldo_tokens)` | `SUM(pote_tokens)` | **Total** | Δ | Origem do Δ |
|---|---|---|---|---|---|
| Banco pristino (`make demo`, 34 migrations) | 10689 | 156 | **10845** | — | baseline |
| Depois do **aporte de 500** (item 2.3) | 11189 | 156 | **11345** | **+500** | **ponta que EMITE** |
| Depois do **ciclo completo do webhook** (item 2.4, crédito de 64) | 11125 | 220 | **11345** | **0** | dentro do ciclo |
| Depois do **resgate de 15** (item 2.5) | 11110 | 220 | **11330** | **−15** | **ponta que QUEIMA** |
| Depois dos **5 ciclos** do script (TRIBO+COLETA+AJUDA+ENTREGA+sem-patrocínio) | 11110 | 220 | **11330** | **0** | dentro do ciclo |

Leitura das duas partes, que **não** podem ser encurtadas numa:

- **Dentro do ciclo, nas quatro categorias, Δ = 0.** Cinco ciclos completos (um pelo webhook, quatro
  pelo script) moveram 64+38+35+30+66 = 233 tokens de pote para carteira, e o total global não se
  mexeu: 11345 → 11345 e 11330 → 11330. Repare que o webhook **subiu** `SUM(pote_tokens)` de 156 para
  220 enquanto **baixou** `SUM(saldo_tokens)` na mesma medida — as duas missões abertas do
  `enviar.sh` ficaram com pote financiado, e é isso que faz a soma ser a invariante certa, e não
  cada parcela.
- **Nas pontas, a soma muda, e só ali.** +500 no `APORTE_PATROCINADOR`, −15 no `RESGATE`.
  10845 + 500 − 15 = **11330**, que é o total final medido. O ledger corrobora:

```
    k     |   v
----------+-------
 aportes  | 10500      (3 lançamentos APORTE_PATROCINADOR — 10000 de seed + 500 meus)
 resgates |    15      (1 lançamento RESGATE)
```

E o `RESGATE` é mesmo sumidouro, não transferência disfarçada:

```
 motivo  | valor_tokens | sem_contraparte | sem_missao
---------+--------------+-----------------+------------
 RESGATE |           15 | t               | t
```

**CONFORME.** Vale registrar a corroboração cruzada: `README.md:129` afirma *"baseline e final em
10845"* para a medição de 2026-08-22, e o banco pristino de hoje mediu **exatamente 10845**. O seed
é reprodutível e o número do README é auditável.

### 3.3 `integro=true` o tempo todo — e por que isso NÃO é prova de conservação

`GET /api/v1/admin/carteiras/reconciliacao`, nos quatro pontos:

```
pós-aporte : {"carteirasVerificadas":13,"integro":true,"divergencias":[],
              "potesImobilizados":{"missoes":0,"tokens":0,"limiar":"PT96H"}}
pós-resgate: {"carteirasVerificadas":13,"integro":true,"divergencias":[],"potesImobilizados":{…0,0…}}
pós-script : {"carteirasVerificadas":14,"integro":true,"divergencias":[],"potesImobilizados":{…0,0…}}
```

`integro` respondeu `true` **inclusive quando a soma global mudou de 10845 para 11345** — ou seja,
enquanto 500 tokens eram criados do nada, do ponto de vista de um estoque. Isso é o comportamento
correto e é exatamente a razão de o ADR 0032 manter os dois campos separados: `integro` compara
**ledger × projeção de saldo**, e um `APORTE_PATROCINADOR` grava as duas pontas coerentemente. Se
alguém fundir `potesImobilizados` em `integro`, esta tabela de cinco linhas deixa de ser lisível — e
a única pergunta que `integro` responde com precisão some junto. A distinção que o `CLAUDE.md`
insiste em manter **está medida aqui**, não só declarada.

`carteirasVerificadas` subiu de 13 para 14 porque o ciclo 5 do script cadastra o patrocinador
`transportadora-sem-saldo`; `potesImobilizados` ficou em 0 porque nada ficou parado além do limiar
`PT96H` (os potes abertos têm minutos de idade).

---
## Item 4 — Há código órfão?

### 4.1 Método: JaCoCo × referência textual — e o JaCoCo é execução cheia

```
$ ls -l --time-style=full-iso services/api/target/site/jacoco/jacoco.xml
-rw-r--r--. 1 renan renan 819134 2026-09-12 14:06:19.906882910 -0300
```

**Timestamp declarado: 2026-09-12 14:06:19**, build anterior a esta sessão. É execução **cheia**, e
os contadores globais provam:

```
INSTRUCTION missed 1471 covered 18624     -> 92,7 % global (gate exige 80 %)
BRANCH      missed  272 covered   879     -> 76,4 % (o "~75 %" do CLAUDE.md, confirmado)
LINE        missed  335 covered  4266
METHOD      missed  116 covered  1090
CLASS       missed    0 covered   313     <- ZERO classes sem execução: a suíte inteira rodou
pacotes 25 · classes 378
```

`CLASS missed 0` é o que autoriza usar este relatório como filtro de órfão: se alguma classe não
tivesse sido carregada, "método nunca executado" seria ambíguo entre órfão e suíte incompleta.

### 4.2 As 19 classes Java novas: **nenhum método com zero execução** — CONFORME

Janela confirmada:

```
$ git diff --stat d02828b..HEAD | tail -1
 89 files changed, 7969 insertions(+), 209 deletions(-)
$ git diff --name-status --diff-filter=A d02828b..HEAD -- '*.java' | grep main/ | wc -l
19
```

Cruzamento método a método das 19 classes contra o JaCoCo:

```
--- classes do alvo ausentes do jacoco: []
(nenhuma linha "ZERO" impressa)
```

Ou seja: **todo método de toda classe nova executou ao menos uma vez.** A varredura preliminar está
**confirmada** — não há método público sem chamador no código de F14–F17.

### 4.3 Os seis candidatos, um a um

**(1) `ReenfileiramentoResponse.idAuditoria()` — NÃO é órfão. CONFORME.**

```
== com/omnitribo/compartilhado/api/ReenfileiramentoResponse
   <init>(…)                cov=18 miss=0
   de(…OutboxEvento…)       cov=13 miss=0
   idAuditoria()            cov= 3 miss=0     <- executou
```

O caminho por reflexão do `AuditoriaAspecto` via `RecursoAuditavel` é exercitado. Confirmação
independente no teste: `OutboxAdminTest.java:319-326` chama o endpoint e depois **consulta a tabela
`auditoria` por SQL** para exigir que `entidade_id` chegou preenchido — o comentário ali
(*"só um teste de integração garante que o `entidade_id` chegou PREENCHIDO até a tabela"*) é honesto
e a assertion existe logo abaixo dele.

**(2) `DiagnosticoPotesService.ContagemPotes` — resolve. NÃO é órfão. CONFORME.**

Referência por string em `MissaoRepository.java:263`:

```sql
select new com.omnitribo.missoes.dominio.DiagnosticoPotesService$ContagemPotes(
       count(m), coalesce(sum(m.poteTokens), 0L))
from Missao m
where m.poteTokens > 0
  and m.status not in :terminais
  and (case when m.status in :porJanelaFim then m.janelaFim else m.estadoDesde end) < :corte
```

Três provas independentes, todas executadas:

```
# a) JaCoCo — o construtor rodou
   DiagnosticoPotesService$ContagemPotes.<init>(JJ)V   cov=9 miss=0
   DiagnosticoPotesService.resumir()                   cov=19 miss=0
   DiagnosticoPotesService.listar(Pageable)            cov=12 miss=0

# b) a query rodou AGORA, contra o banco de pé, e devolveu o record montado
$ curl .../admin/carteiras/reconciliacao      -> "potesImobilizados":{"missoes":0,"tokens":0,"limiar":"PT96H"}
$ curl .../admin/missoes/potes-imobilizados   -> {"resumo":{"missoes":0,"tokens":0,"limiar":"PT96H"},"pagina":{…}}

# c) o predicado casa linhas — mesmo SQL, corte de 1 min em vez de 96 h
 missoes | tokens
---------+--------
       1 |     42
```

O construtor JPQL é validado por Hibernate **no bootstrap**: um nome errado impediria o contexto de
subir, e o backend está de pé. Somado ao (b), a resolução está provada por execução, não por leitura.

> Detalhe que a consulta (c) expôs de graça: a única missão que casaria com um corte curto é uma
> **`ACEITA` com 42 tokens** — exatamente a linha `varreduraCobre=false` da **pendência 1** do
> `CLAUDE.md` (ACEITA não tem varredura nem porta de ADMIN). A pendência é real e tem sujeito no
> banco de demonstração.

**(3) `PotesImobilizadosResumoResponse` × `ResumoPotesImobilizados` — duplicação JUSTIFICADA. CONFORME.**

Os dois records são `(long missoes, long tokens, String limiar)`, ambos executados
(`cov=12 miss=0` cada). A duplicação é deliberada e a razão está escrita, em
`carteira/api/PotesImobilizadosResumoResponse.java:25-27`:

> *"Os números vêm da porta `missoes/api/DiagnosticoPotes`, no mesmo snapshot transacional que
> produziu `integro`, e são remapeados para este record para que o contrato publicado por `carteira`
> continue sendo de `carteira`."*

Contra o ADR 0018: a regra do ArchUnit **permitiria** `carteira` importar `missoes.api
.ResumoPotesImobilizados` direto — `api/` é porta pública. Logo a duplicação **não é imposta** pela
arquitetura; é escolha de propriedade de contrato. A escolha se sustenta: o schema OpenAPI de
`ReconciliacaoResponse` passaria a referenciar um tipo de outro módulo, e uma mudança no javadoc ou
no `@Schema` de `missoes` alteraria o contrato publicado por `carteira` sem que ninguém em `carteira`
tocasse nada. Seis linhas de record contra acoplamento de contrato é troca favorável.
**CONFORME, com a ressalva de que o javadoc deveria dizer que a alternativa era LEGAL, não proibida** —
como está, sugere restrição arquitetural onde há preferência de desenho.

**(4) `paginaOuPadrao()`/`tamanhoOuPadrao()` em quatro DTOs — LACUNA, e ela está MEDIDA.**

São quatro, não três:

```
carteira/api/BeneficioFiltroRequest.java:41,45
compartilhado/api/OutboxFiltroRequest.java:15,19
logistica/api/RecusaFiltroRequest.java:24,28
missoes/api/PotesImobilizadosFiltroRequest.java:17,21
```

Corpo idêntico nos quatro: `return pagina == null ? 0 : pagina;` / `return tamanho == null ? 20 : tamanho;`.

Nenhum teste os nomeia:

```
$ grep -rln "paginaOuPadrao|tamanhoOuPadrao" --include=*.java services/api/src/test
(vazio)
```

E o JaCoCo mostra **qual metade nunca executou** — é o `miss=3`, que é exatamente o ramo do valor
ausente:

| DTO | `paginaOuPadrao` | `tamanhoOuPadrao` |
|---|---|---|
| `BeneficioFiltroRequest` | cov=6 **miss=3**, branch 1/1 | cov=9 miss=0, branch 2/0 |
| `OutboxFiltroRequest` | cov=6 **miss=3** | cov=9 miss=0 |
| `RecusaFiltroRequest` | cov=6 **miss=3** | cov=6 **miss=3** |
| `PotesImobilizadosFiltroRequest` | cov=6 **miss=3** | cov=6 **miss=3** |

**Cinco de oito ramos de default nunca foram executados pela suíte.** O código não é órfão — os
métodos rodam —, mas o *padrão* que eles existem para dar é não exercitado em 5 dos 8 casos. Isso é
**LACUNA**, não defeito, e a diferença importa: eu exercitei os defaults ao vivo e eles funcionam.

```
GET /api/v1/admin/outbox/esgotados                  -> 200  {"pagina":0,"tamanho":20,…}
GET /api/v1/admin/outbox/esgotados?pagina=0&tamanho=20 -> 200
GET /api/v1/admin/outbox/esgotados?tamanho=101      -> 400   (@Max(100) barra)
GET /api/v1/admin/outbox/esgotados?pagina=-1        -> 400   (@Min(0) barra)
GET /api/v1/admin/missoes/potes-imobilizados        -> {"pagina":0,"tamanho":20,…}
```

O que quebra por faltar: hoje nada. Se alguém trocar o `20` por `200` num dos quatro (ou remover o
`@Max(100)` de um), a suíte continua verde e a API passa a aceitar página de 200 linhas — num
endpoint ADMIN cujo corpo contém `ultimo_erro` de outbox e agregação de recusa por ponto. É um teste
de unidade de quatro assertions.

**(5) `apps/mobile/src/testes/perfilador.tsx` — um importador, e ele é legítimo. CONFORME.**

```
$ grep -rn "perfilador" apps/mobile --exclude-dir=node_modules | grep -v "src/testes/perfilador.tsx"
apps/mobile/app/__tests__/renderizacao.test.tsx:8:import { novoPerfil } from '@/testes/perfilador';
```

Um importador só, mas é um utilitário de teste — um só consumidor é o estado esperado, não órfão.
E a suíte que o importa passa (§6.2: `18 passed`).

**(6) `app/(app)/impacto.tsx` — nenhuma referência sobrou. CONFORME (candidato REFUTADO).**

A F17 removeu os dois estilos:

```
$ git diff 7c58e0b..61cc05e -- 'apps/mobile/app/(app)/impacto.tsx' | grep '^-'
-  titulo: { ...tipografia.titulo, color: cores.tinta },
-  subtitulo: { ...tipografia.subtitulo, color: cores.tinta, marginTop: espaco.sm },
```

E não há referência pendente. O objeto se chama `estilos`, não `styles` — conferi os dois nomes:

```
$ grep -n "estilos\.titulo|estilos\.subtitulo|styles\.titulo|styles\.subtitulo" 'apps/mobile/app/(app)/impacto.tsx'
(nenhuma)

$ npx tsc --noEmit -p tsconfig.json ; echo $?
0
```

As ocorrências de `titulo=` em `impacto.tsx:47,98` são **props JSX** de componentes do design system,
e `tipografia.subtitulo` em `:403,412` é o token de tipografia — nenhuma toca o `StyleSheet` removido.

### 4.4 Prefixo sentinela `eeee1111-` — DEFEITO (latente), confirmado

```
$ grep -rn "eeee1111" --include=*.java services/api/src
compartilhado/infra/PlanoConsultasQuentesTest.java:54:  private static final String PREFIXO_MISSAO     = "eeee1111-0000-0000-0000-";
missoes/infra/IndicePoteImobilizadoTest.java:48:      private static final String PREFIXO_SINTETICO = "eeee1111-0000-0000-0000-";
```

Os dois javadocs **enumeram os outros usuários do espaço de nomes e nenhum lista o outro**:

```java
// IndicePoteImobilizadoTest.java:47
/** Prefixo sentinela próprio: o seed usa dddddddd-…, o teste geoespacial usa eeee0000-…. */

// PlanoConsultasQuentesTest.java:53
/** Prefixos sentinela. O seed usa dddddddd-/bbbbbbbb-; IndiceGeoespacialTest usa eeee0000-. */
```

E os dois `@AfterAll` varrem a **mesma** faixa:

```java
// IndicePoteImobilizadoTest.java:155-158
jdbc.update("DELETE FROM missao WHERE id::text LIKE ?", PREFIXO_SINTETICO + "%");   // eeee1111-%

// PlanoConsultasQuentesTest.java:334-340
jdbc.update("DELETE FROM missao WHERE id::text LIKE ?", PREFIXO_MISSAO + "%");      // eeee1111-%
```

Um deles semeia 50 000 linhas, o outro 200 000, **na mesma tabela, com o mesmo prefixo**.

**Por que não explode hoje**, medido e não suposto:

```
$ find services/api/src/test -name junit-platform.properties
(nenhum arquivo — sem execução paralela configurada)
$ grep -n "parallel|forkCount" services/api/pom.xml
(nenhuma linha)
```

Suíte sequencial, e todos os `@Test` que semeiam declaram `@Transactional`
(`IndicePoteImobilizadoTest:57`, `PlanoConsultasQuentesTest:76,160`). O terceiro teste de
`PlanoConsultasQuentesTest:212` não tem `@Transactional`, mas também **não semeia** — só assere sobre
string JPQL e enum, então não vaza linha. Confirmação no banco de dev: `SELECT count(*) FROM missao
WHERE id::text LIKE 'eeee1111-%'` → **0**.

**Por que ainda é defeito.** Duas coisas ficam afirmadas e falsas:

1. O adjetivo **"próprio"** em `IndicePoteImobilizadoTest:47` é literalmente incorreto — o prefixo é
   compartilhado com outra classe, e a lista existe justamente para que a próxima pessoa escolha um
   prefixo livre. Quem ler essa linha para criar o terceiro teste de volume vai acreditar que
   `eeee1111-` está tomado por uma classe só.
2. A consequência não é hipotética, é **condicional a uma mudança de uma linha**: ligar
   `junit.jupiter.execution.parallel.enabled`, ou remover um `@Transactional` (que é o caminho
   natural — o próprio `PlanoConsultasQuentesTest:71` já argumenta que `@Transactional` semearia de
   novo e considera tirá-lo), e o `@AfterAll` de uma classe passa a apagar a fixture da outra
   **durante** a execução. O sintoma seria um `EXPLAIN` medindo tabela vazia, isto é, um teste de
   plano **verde com plano errado** — falso negativo, o pior modo de falha para este tipo de teste.

Observe que os `@AfterAll` **já são o vetor que a `varredura-orfaos.md` admite não cobrir**: com
`@Transactional` ativo o rollback já desfez tudo, então cada `DELETE` casa **zero linhas** hoje — é
"limpeza sem alvo". Os dois comentários são honestos sobre isso (*"Cinto e suspensório"*,
`IndicePoteImobilizadoTest:149` e `PlanoConsultasQuentesTest:331`), e inclusive se referem um ao
outro como *"o irmão"* / *"o teste geoespacial"* — o que torna a omissão nos javadocs do prefixo
ainda mais claramente um lapso de redação, não desconhecimento.

**Correção:** trocar um dos dois prefixos (p. ex. `eeee5555-` em `IndicePoteImobilizadoTest`) e fazer
cada javadoc citar o outro. Zero risco, dois arquivos de teste.

### 4.5 Configuração órfã: nenhuma das duas chaves novas é órfã — CONFORME

```
app.missoes.diagnostico.pote-imobilizado-apos: PT96H     (application.yml:139)
  lida em  missoes/dominio/DiagnosticoPotesService.java:62   @Value("${…:PT96H}") Duration limiar
  asserida em missoes/api/PoteImobilizadoTest.java:82        @Value("${…}")
  ECOADA ao vivo: "limiar":"PT96H" na reconciliação e no diagnóstico

app.notificacoes.janela-alerta-operacional: PT1H          (application.yml:278)
  lida em  notificacoes/dominio/ParametrosNotificacoes.java:59,78-85  (com validação de mínimo 1 s)
  usada em notificacoes/dominio/DespachanteAlertaService.java:377
  asserida em logistica/api/DespachanteAlertaOperacionalTest.java:157
  também em test/resources/application-test.yml:68
```

A primeira tem prova de ponta a ponta que não depende de leitura: o valor do YAML **aparece na
resposta HTTP**, literalmente, e é por isso que `ResumoPotesImobilizados.java:16` documenta o campo
como "o valor de `app.missoes.diagnostico.pote-imobilizado-apos`, literalmente, para que a resposta e
a configuração possam ser comparadas a olho". Comparei: batem.

### 4.6 Os 116 métodos sem execução no projeto inteiro — ruído, com DUAS exceções

```
total de métodos com 0 instrução coberta: 116
  31  carteira/dominio      25  identidade/dominio     24  logistica/dominio
  11  compartilhado/infra    7  missoes/dominio         4  identidade/api
   3  geolocalizacao/dominio 3  compartilhado/api       2  notificacoes/dominio
   1  cada: missoes/api, logistica/api, compartilhado/dominio, carteira/api, integracoes/infra, raiz
```

A amostra confirma o diagnóstico que o `CLAUDE.md` já previa ao recusar mutation testing global:
são **getters JPA e lambdas** — `EntregaFalida.getDestinoUf`, `Checkin.getMissaoId`,
`Alerta.getPrioridade`, `MissaoService.lambda$aplicar$7`. Nada disso é lógica órfã.

**As duas exceções que merecem nome**, e não são do código novo (estão fora da janela
`d02828b..HEAD`, portanto **não são achado desta entrega** — registro para a ordem de correção):

```
GlobalExceptionHandler.handleConflitoConcorrencia   cov=0    (ObjectOptimisticLockingFailureException -> 409)
GlobalExceptionHandler.handleIntegridade            cov=0    (SQLState 22001 -> 400)
GlobalExceptionHandler.sqlState                     cov=0    (o walker da cadeia de causas)
```

Três mapeamentos da superfície RFC 9457 que **a suíte inteira nunca executou**, e nenhum teste os
menciona:

```
$ grep -rn "22001|ObjectOptimisticLocking" --include=*.java services/api/src/test
(vazio)
```

Tentei disparar o 22001 ao vivo, e o resultado é tranquilizador mas não substitui teste — a validação
de bean intercepta antes:

```
$ curl -X POST /api/v1/auth/registrar -d '{"nome":"AAA…(400 chars)",…}'
{"detail":"Um ou mais campos falharam na validação.","status":400,
 "type":"https://omnitribo.dev/problemas/requisicao-invalida",
 "errors":[{"campo":"nome","mensagem":"tamanho deve ser entre 0 e 100"},…]}
```

Ou seja: a defesa em profundidade está na ordem certa (borda antes do banco), e por isso mesmo o
handler de 22001 só serviria para campo de terceiro — o webhook, cuja assinatura HMAC eu não
reproduzi por fora do `enviar.sh`. **LACUNA de teste**, herdada, de baixo impacto.

---

## Item 5 — Comentário afirmando garantia que o código não dá?

### 5.1 DEFEITO confirmado — `V29__dedup_alerta_operacional.sql:62` nomeia uma classe que não existe

```
$ grep -rn "DespachanteAlertaPontoLotadoTest" --include=*.java --include=*.sql --include=*.md .
services/api/src/main/resources/db/migration/V29__dedup_alerta_operacional.sql:62:-- isso é DespachanteAlertaPontoLotadoTest, não o banco.

$ find . -name 'DespachanteAlerta*Test.java' -not -path '*/node_modules/*'
./services/api/src/test/java/com/omnitribo/logistica/api/DespachanteAlertaOperacionalTest.java
```

**Uma única ocorrência no repositório inteiro, e é a que inventa o nome.** O contexto (V29:59-62):

> `-- O QUE ISTO NÃO GARANTE, … ele não obriga ninguém a preencher as colunas. Um caminho de escrita`
> `-- novo que grave alerta global com`referencia`nula fica FORA do predicado e não é deduplicado —`
> `-- sem erro, sem aviso. Quem cobre isso é DespachanteAlertaPontoLotadoTest, não o banco.`

Dois erros no mesmo ponteiro: o **nome** (`…PontoLotadoTest` × `…OperacionalTest`) e o **lugar**
implícito — a classe real mora em `logistica/api/`, não em `notificacoes/`, porque quem dispara o
alerta é o webhook de logística.

Efeito prático, e é o que torna isso DEFEITO e não erro de digitação: este comentário é a **única**
indicação, em qualquer lugar do repositório, de onde está a cobertura do bypass que o parágrafo acima
acaba de admitir existir. Quem auditar a dedup procura a classe nomeada, não acha, e conclui que o
bypass está descoberto — é exatamente o erro de leitura que a `varredura-orfaos.md` §5.4 cometeu.
Correção: uma palavra.

### 5.2 O bypass documentado é REAL — reproduzido, `INSERT 0 3`

Executado num `BEGIN … ROLLBACK` (resíduo conferido em 0 no fim):

```
BEGIN
-- A: mesma (tipo, referencia, janela) três vezes, com o WHERE de AlertaRepository:105-107
INSERT 0 1
INSERT 0 0
INSERT 0 0
            cenario             | linhas
--------------------------------+--------
 A: 3 tentativas COM referencia |      1      <- dedup FUNCIONA

-- B: alerta global, referencia NULL, três linhas de uma vez
INSERT 0 3
               cenario               | linhas
-------------------------------------+--------
 B: 3 tentativas com referencia NULL |      3      <- fora do índice parcial, NÃO deduplicado
ROLLBACK
       cenario        | count
----------------------+-------
 residuo pos-rollback |     0
```

O comentário da V29 está **certo** sobre a garantia (ele declara o bypass em voz alta) e **errado**
só sobre quem o cobre. É a combinação boa: a afirmação de segurança é honesta, o ponteiro é que
apodreceu.

### 5.3 EXCEDENTE — `AlertaRepository.java:88-92` afirma um erro exato, e eu o reproduzi por acidente

O javadoc diz:

> *"O `WHERE` depois do `ON CONFLICT` não é decoração e não pode ser encurtado. Ele repete, palavra
> por palavra, o predicado de `uk_alerta_operacional`. Sem isso o PostgreSQL não consegue inferir
> qual índice arbitra e responde **"there is no unique or exclusion constraint matching the ON
> CONFLICT specification"** — verificado contra o banco antes de escrever esta query, não deduzido da
> documentação."*

Minha primeira tentativa de reproduzir a dedup omitiu o `WHERE`. Saída literal:

```
BEGIN
ERROR:  there is no unique or exclusion constraint matching the ON CONFLICT specification
```

Mensagem idêntica, caractere por caractere. Um comentário que prevê a mensagem de erro exata de um
atalho plausível é **EXCEDENTE**: ele não documenta o que o código faz, documenta a armadilha que
alguém vai pisar ao "simplificar".

### 5.4 `Alerta.java:25-31` — as três afirmações checadas, as três verdadeiras. CONFORME.

| Afirmação | Verificação executada |
|---|---|
| "A V29 acrescentou duas colunas que esta entidade NÃO mapeia" | `\d alerta` mostra `referencia varchar(100)` e `janela_inicio timestamptz`; a entidade não as declara |
| "são gravadas só pelo `INSERT … ON CONFLICT` de `AlertaRepository.inserirOperacionalSeAusente`" | `AlertaRepository.java:96-118` é o único escritor das duas colunas |
| "`ddl-auto: validate` não se importa com coluna não mapeada" | `application.yml:11` é `validate`, e **o backend está de pé** com as duas colunas fora do mapeamento — a prova é o boot, não a leitura |

### 5.5 `PoteImobilizadoResponse.java:29-30` afirma uma IMPOSSIBILIDADE — e ela se sustenta. CONFORME.

> *"Quanto a missão promete pagar. **Menor que `poteTokens` é impossível** para missão publicada;
> maior significa rascunho ainda subfinanciado."*

Valia medir, porque **o banco não impede**:

```
$ SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='missao'::regclass AND contype='c';
 missao_categoria_check · ck_missao_status · ck_missao_pote_nao_negativo (pote_tokens >= 0)
 ck_missao_economia (valor_brl = 0) · ck_missao_complexidade · ck_missao_nivel_minimo
 ck_missao_faixa_risco · ck_missao_fonte_pote
(8 rows — nenhuma relaciona pote_tokens com tokens_recompensa)
```

Como o `FinanciamentoService` aceita financiar missão `ABERTA`, tentei sobrefinanciar uma já 100 %
coberta (38/38):

```
$ curl -X POST /api/v1/tribos/aaaaaaaa-…0901/financiamentos -d '{"missaoId":"dddddddd-…0904","tokens":10}'
{"detail":"Pote ficaria com 48 tokens, acima da recompensa de 38. Faltam apenas 0.",
 "status":422,"type":"https://omnitribo.dev/problemas/regra-negocio-violada"}

$ SELECT status, tokens_recompensa, pote_tokens FROM missao WHERE id='dddddddd-…0904';
 ABERTA | 38 | 38          <- intacto
```

A impossibilidade é garantida pela aplicação, com 422 e mensagem específica. **CONFORME** — e vale
registrar que a garantia é de **uma** camada, não duas: um `UPDATE` direto no banco a violaria.
Diferente de `ck_missao_economia` (BRL) e `ck_beneficio_sem_reais`, que têm `CHECK`. Não é defeito —
sobrefinanciamento não é fraude de valor, só um pote maior que a promessa —, mas o javadoc diz
"impossível" onde o rigor seria "a borda recusa".

### 5.6 `ContadorDeQueries.java:24-29` — "não há uma única associação JPA" — CONFORME, medido

```
$ grep -rn "@ManyToOne|@OneToMany|@OneToOne|@ManyToMany|@ElementCollection" --include=*.java services/api/src/main/java | wc -l
1
services/api/src/main/java/com/omnitribo/carteira/dominio/Lancamento.java:45:
  // UUID puro, SEM FK e SEM @ManyToOne — fronteira crítica carteira→missoes.
```

A única ocorrência é um comentário dizendo que não há. Afirmação verdadeira, e o `ContadorDeQueries`
existe justamente porque ela é uma propriedade sem guardião — o comentário é explícito sobre isso
(*"nada falhava no dia em que alguém trocasse `UUID criadorId` por `@ManyToOne Usuario criador`"*).
Honesto.

### 5.7 `OutboxAdminTest.java:319` — "o ArchUnit garante que o tipo de retorno implementa
`RecursoAuditavel`" — CONFORME

A regra existe, e não é genérica:

```
services/api/src/test/java/com/omnitribo/arquitetura/RegrasArquiteturaTest.java:55
  new ArchCondition<JavaMethod>("devolver um tipo que implementa RecursoAuditavel") {
:58   if (!metodo.getRawReturnType().isAssignableTo(RecursoAuditavel.class)) {
:65     … "que não implementa RecursoAuditavel —"
```

### 5.8 As afirmações de "at-least-once" de `ace6b14` continuam corretas no código novo

Varredura do diff `d02828b..HEAD` pelos verbos de garantia. O padrão do código novo é afirmar o
**negativo**, que é o registro defensável:

```
+ * drenador a despacha, sem atalho. <b>Isso NÃO torna a entrega at-least-once</b>, e a distinção
+ …"pendências. A entrega NÃO é at-least-once: o drenador da outbox para após 5 "
+ * silenciosa" para "perda detectável", e não para "entrega garantida", porque nada avisa que há
+-- 2. O índice que deduplica — e o que ele NÃO garante.
+-- O QUE ISTO NÃO GARANTE, e precisa ser dito porque a leitura do índice sugere o contrário: …
+ * torna o N+1 clássico impossível <i>hoje</i> — e é exatamente por isso que ninguém escreveu teste
```

Nenhuma afirmação NOVA de garantia inexistente foi encontrada além da 5.1. **CONFORME.**

### 5.9 O que NÃO consegui verificar, e declaro como não verificado

O escopo desta auditoria proíbe `./mvnw` (disputa de `target/` com o backend de pé). Logo **não
confirmei que os controles com teste falham quando deveriam** — não inverti nenhuma assertion de
`PoteImobilizadoTest`, `DespachanteAlertaOperacionalTest` ou `RegrasArquiteturaTest`. O que tenho é o
JaCoCo de 14:06 provando que os métodos executaram, o que é mais fraco: cobertura não é assertion
viva. **NÃO VERIFICADO — bloqueado por instrução, não por impossibilidade.** É o trabalho natural do
passo de verificação formal que sucede esta auditoria.

---

## Item 6 — Divergência de documentação nova?

Base: `docs/DIVERGENCIAS-DOCUMENTACAO.md`, **2026-08-16 · F13** (confirmado no cabeçalho, linha 3).
O que está lá não é achado novo. Confirmei cada linha da tabela do escopo e acrescentei três.

### 6.1 Contagem de testes — **DEFEITO**: três números, nenhuma evidência para o publicado

| Onde | Diz | Realidade medida |
|---|---|---|
| `README.md:74` | *"verificação executada em **2026-08-25**"* citando `f13-make-test.md` | `docs/evidencias/f13-make-test.md:3` → **`Data: 2026-08-16 · Fase: F13`** |
| `README.md:78` | Backend **706** testes, **68** classes | `f13-make-test.md:11` registra `Tests run: 637`; em disco, **76** classes `*Test.java` |
| `README.md:79` | Mobile **221** testes, **17** suítes | **medido agora: 225 testes, 18 suítes** |
| `docs/PROGRESSO.md:109` | *"A suíte foi de 735 para **744**"* | terceiro número, sem evidência própria |

Medição do mobile, executada:

```
$ cd apps/mobile && npx jest --ci
Test Suites: 18 passed, 18 total
Tests:       225 passed, 225 total
Time:        5.738 s
```

Contagem em disco:

```
$ find services/api/src/test -name '*Test.java' | wc -l
76
$ find apps/mobile -name '*.test.ts' -o -name '*.test.tsx' | wc -l   (menos 2 de .e2e., ignorados por jest.config.js:21)
18
```

E **nenhuma evidência de execução em 2026-08-25 existe** para `make test`:

```
$ grep -rln "2026-08-25" docs/evidencias/
docs/evidencias/README.md
docs/evidencias/f21-carga.md              <- k6, testes de CARGA, não `make test`
docs/evidencias/desempenho-antes-de-otimizar.md
```

Classificação **DEFEITO**, e não simples desatualização, por dois motivos: (a) o README **cita um
arquivo** como fonte, e o arquivo diz outra data e outros números — é uma citação que não sustenta a
afirmação; (b) os números publicados (706/221) não aparecem em evidência nenhuma, enquanto três
outros números (637, 744, 225) aparecem em três lugares diferentes. O enunciado da atividade pede
"testado" e "acompanhado das informações necessárias para que outra pessoa compreenda" — a informação
está, mas não confere, e é o primeiro número que um avaliador cruza.

### 6.2 Contagens do rodapé do README — **DEFEITO** (menor), aritmética simples

```
README.md:508  "30 decisões"   ->  $ ls docs/adr/*.md | wc -l  = 34, menos TEMPLATE.md = 33  (0001–0033)
README.md:509  "12 documentos" ->  $ ls docs/auditoria/*.md | wc -l = 13  (14 com este relatório)
```

### 6.3 Faixas de migration no README — **DEFEITO**, e o boot de hoje é a prova

```
README.md:311-312  "quem aplica o schema (V1–V22) e depois o seed (V900–V904) é o Flyway"

$ ls services/api/src/main/resources/db/migration/*.sql | wc -l   -> 27   (V1–V8, V11–V29)
$ ls services/api/src/main/resources/db/seed/*.sql | wc -l        ->  7   (V900–V906)
$ SELECT count(*) FROM flyway_schema_history;                     -> 34   = 27 + 7
```

Os três números fecham entre si e contradizem o README. Consequência prática: quem lê o README e
depois vê 34 linhas no histórico do Flyway suspeita do banco, não do texto. E a faixa errada é
perigosa perto do aviso sobre **V9 e V10 queimadas**: um leitor que acredite que o schema vai até V22
pode escolher V23 para uma migration nova — que já existe.

### 6.4 Tabela de fases sem F14–F17 — **LACUNA**, e mais funda que o escopo previa

```
$ sed -n '3,21p' docs/PROGRESSO.md
… F0 … F13 (2026-08-16) … F12b (2026-08-25) … F12c … F21 (2026-08-24)
(nenhuma linha F14, F15, F16 ou F17)
```

Os nove commits da janela `d02828b..HEAD` produziram **quatro PRs de fase (#34–#39)**. Situação de
cada um:

| Fase (branch) | Linha na tabela | Nota de manutenção | Citada nos `CLAUDE.md` |
|---|---|---|---|
| F14 (carta-morta, pote imobilizado, dedup) | **não** | **sim** — `PROGRESSO.md:56, 113, 182` (2026-09-10 e 09-11) | sim |
| F15 (`ContadorDeQueries`, perfilador, desempenho) | **não** | **não** | **não** |
| F16 (`make demo`, `tools/demo/`, `PLANO-B.md`) | **não** | **não** | **não** |
| F17 (faixa de sensibilidade, `impacto.tsx`) | **não** | **não** | **não** |

```
$ for k in ContadorDeQueries perfilador "make demo" "tools/demo" "PLANO-B" "faixa de sensibilidade"; do …
ContadorDeQueries                raiz=0 api=0 mobile=0 README=0  PROGRESSO=0
perfilador                       raiz=0 api=0 mobile=0 README=0  PROGRESSO=0
make demo                        raiz=0 api=0 mobile=0 README=0  PROGRESSO=0
tools/demo                       raiz=0 api=0 mobile=0 README=0  PROGRESSO=0
PLANO-B                          raiz=0 api=0 mobile=0 README=0  PROGRESSO=0
faixa de sensibilidade           raiz=0 api=0 mobile=0 README=0  PROGRESSO=0
```

**Zero menções em quatro documentos.** Os artefatos existem (`docs/PLANO-B.md`, `tools/demo/`,
`Makefile:36`, `docs/evidencias/desempenho-antes-de-otimizar.md`) e eu os usei nesta auditoria.
A F14 **está** documentada, como o escopo dizia — mas por nota de manutenção, não por linha de fase.

**O que quebra por faltar:** o `CLAUDE.md` da raiz é a única fonte de verdade declarada sobre estado
do projeto, e ele diz *"F13 (entrega final) concluída em 2026-08-16"* como último marco. Três fases
depois disso são invisíveis a quem só lê a documentação — incluindo o comando que prepara a
demonstração.

### 6.5 **ACHADO NOVO (1)** — o número de fase F16/F17 já está OCUPADO por outra coisa

Isto não estava na tabela do escopo e é a parte mais séria do problema de "organizado":

```
$ grep -n "F16" apps/mobile/CLAUDE.md
146: enquanto o sumidouro não existia; a F16 (V24-V26, ADR 0027) o trouxe, e `src/features/beneficios/

$ grep -n "F17" docs/PROGRESSO.md
480: tinha aparecido na F17 e foi contornado em silêncio; como agora era a espinha do artefato, a
```

`apps/mobile/CLAUDE.md:146` usa **"F16"** para o resgate/sumidouro (V24–V26, ADR 0027) — que não é a
F16 da branch `feat/f16-preparo-demo`. E `PROGRESSO.md:480` usa **"F17"** para a fase de
acessibilidade — que não é a F17 de `feat/f17-faixa-sensibilidade-impacto`.

Ou seja: os rótulos F16 e F17 **designam duas fases diferentes cada um**, em documentos diferentes, e
nenhum dos dois sentidos está na tabela de fases. Isto é pior que a ambiguidade do prefixo `f21-` já
declarada no `CLAUDE.md`, porque ali há um aviso explícito (*"Cuidado com o prefixo `f21-`: ele cobre
DUAS fases diferentes"*) e aqui não há nenhum. **DEFEITO de documentação.**

### 6.6 **ACHADO NOVO (2)** — `PROGRESSO.md:11` marca F8 como "🟨 Parcial", o `CLAUDE.md` a declara fechada

```
$ sed -n '11p' docs/PROGRESSO.md
| F8   | Logística, notificações e patrocinador| 🟨 Parcial  | —         | 2026-08-14 |
```

Contra o `CLAUDE.md` da raiz: *"**F8 fechou em 2026-08-20 com a carteira de patrocinador** (ADR
0024)"*. A tabela ainda diz 2026-08-14 e "Parcial", **e a coluna Auditoria é `—`**. A implementação
está de pé — eu exercitei os doze cenários do webhook, a conversão, o financiamento por patrocinador
e a baixa de custódia hoje. A tabela de fases é o primeiro lugar que um avaliador olha, e ela marca
como incompleto o módulo que carrega a tese do produto. **DIVERGÊNCIA**, com efeito de subestimar a
própria entrega.

### 6.7 Usuários do seed sem `renan@omnitribo.dev` — **DEFEITO**

```
$ sed -n '404,411p' README.md
| `alice@omnitribo.dev` | Usuário | Pinheiros |
| `bob@omnitribo.dev`   | Usuário | Vila Madalena |
| `carol@omnitribo.dev` | Usuário | Vila Madalena |
| `admin@omnitribo.dev` | Admin   | Pinheiros |
```

`renan@omnitribo.dev` **é o protagonista do `ROTEIRO-DEMO.md`** (linha 13: *"Tudo na zona leste,
tribo Cidade Líder, com `renan@omnitribo.dev`"*) e existe no seed:

```
 bbbbbbbb-0000-0000-0000-000000000901 | renan@omnitribo.dev | saldo 124 | Tribo Cidade Líder
```

O `CLAUDE.md` também não o lista na sua linha de usuários seed. Faltam ainda `diana@` e `erik@`, que o
`CLAUDE.md` cita e o README não. **DEFEITO**: a única conta que o roteiro usa é a única que a
documentação de execução não menciona.

### 6.8 `make demo` não aparece no README — **LACUNA**

```
$ grep -c "make demo" README.md docs/ROTEIRO-DEMO.md docs/PLANO-B.md
README.md:0
docs/ROTEIRO-DEMO.md:5
docs/PLANO-B.md:5
```

`Makefile:36` é o preparo oficial e completo da demonstração (chaves + banco do zero + espera pelo
Postgres, com recusa se algo estiver na 8080). Está em dois documentos de demonstração e ausente do
documento de entrada. Combinado com 6.3 (faixas de migration erradas na seção de execução), a seção
"como rodar" do README é a menos confiável do arquivo.

### 6.9 As cinco imagens e o vídeo-pitch — **DEFEITO**, e é o primeiro que se vê

```
$ ls docs/imagens/
README.md

README.md:5   ![O ciclo da tese…](docs/imagens/demo.gif)
README.md:13  ![Radar…](docs/imagens/radar.png) | ![Detalhe da missão…](docs/imagens/missao-checkin.png)
README.md:15  ![Carteira…](docs/imagens/carteira.png) | ![Catálogo de benefícios…](docs/imagens/beneficios.png)
README.md:7   **▶ [Vídeo-pitch (3 min)](COLE-A-URL-DO-VIDEO-AQUI)**
```

Cinco imagens inexistentes e um link com o **texto do placeholder literal**, nas primeiras 15 linhas
do README. A especificação pede "demonstrável"; o topo do README entrega cinco ícones de imagem
quebrada e uma URL que diz `COLE-A-URL-DO-VIDEO-AQUI`. É o achado de **pior relação custo/impacto** do
relatório: nenhum código muda e é o primeiro contato do avaliador com o projeto.

### 6.10 Convenção de nome em `docs/evidencias/` — **DIVERGÊNCIA ACEITÁVEL**

```
docs/evidencias/README.md:62  "- Nome: `f<fase>-<assunto>.md`."
docs/evidencias/desempenho-antes-de-otimizar.md:3  "**Data:** 2026-09-11 · **Branch:** `feat/f15-performance`"
```

O arquivo não tem prefixo, mas **declara a fase no cabeçalho**, que é a informação que a convenção
protege. E como agora existe branch `f15` de verdade, nomeá-lo `f15-desempenho.md` seria *mais*
correto, não menos. Divergência real, consequência nula — o próprio arquivo resolve a ambiguidade na
linha 3. Só não está justificada em lugar nenhum, o que a mantém como divergência e não como decisão.

### 6.11 `docs/evidencias/README.md` §"o que NÃO provam" está **estale** — DEFEITO (menor)

```
docs/evidencias/README.md:51-52
- **Conservação em ENTREGA.** O ciclo de ENTREGA nasce do webhook e envolve ponto de custódia; a
  medição por categoria refez AJUDA e TRIBO. O caso de ENTREGA (Δ=+60) foi medido na …
```

Contradito **pela tabela do mesmo arquivo, doze linhas acima**:

```
docs/evidencias/README.md:16
| f14-conservacao-quatro-categorias.md | 2026-08-22 | Δ=0 nas QUATRO categorias, com o pote de
  ENTREGA pago pelo patrocinador … integro=true em todos os pontos |
```

E contradito pela medição de hoje (item 3): `ENTREGA Δ=0 recompensa=66 (pote pago pelo patrocinador)`,
mais o ciclo do webhook do `enviar.sh`, também Δ=0. Um arquivo que se contradiz internamente sobre a
invariante central do projeto é caro numa banca — é justo a seção que existe para ser lida com
desconfiança.

### 6.12 `DIVERGENCIAS-DOCUMENTACAO.md:99` diz F12b "pendente" — DIVERGÊNCIA, já explicada

```
| 9 | §11.2 — latência < 200 ms e 1.000 TPS | Não medidos. Os testes de carga são a F12b, pendente |
```

F12b fechou em 2026-08-25 (`PROGRESSO.md:17`, `docs/evidencias/f21-carga.md`). Mas o próprio
documento se declara **datado**: cabeçalho `Data: 2026-08-16 · Fase: F13`, e `evidencias/README.md:66`
tem a regra *"Evidência não se edita para 'melhorar' o resultado. Se o número mudou, gere uma
evidência nova com data nova."* Um documento datado que ficou desatualizado, e diz sua própria data,
é **DIVERGÊNCIA ACEITÁVEL** — a correção é uma linha de adendo, não uma reescrita.

### 6.13 `docs/qualidade/verificacao-*` para em 2026-08-16 — LACUNA

```
$ ls docs/qualidade/
verificacao-2026-08-05.md  verificacao-2026-08-06.md  verificacao-2026-08-15.md  verificacao-2026-08-16.md
```

Quatro fases (F14–F17) sem verificação formal registrada. O `CLAUDE.md` já avisa que as verificações
de 08-07 e 08-11 vivem nas Notas de manutenção, então o padrão admite as duas formas — mas F15, F16 e
F17 não estão em **nenhuma** das duas (§6.4). **LACUNA**, e é a mesma de 6.4 vista do outro lado.

### 6.14 "11 telas + rota-porta" — **DIVERGÊNCIA**: são 12

```
$ find apps/mobile/app -name '*.tsx' | wc -l    -> 26
   9 são __tests__/ · 5 são _layout.tsx · 1 é app/index.tsx (a rota-porta)
   = 12 telas:  (app)/beneficios · (app)/impacto · (app)/missao/criar · (app)/missao/[id]
                (auth)/login · (auth)/registrar · onboarding
                (tabs)/index · (tabs)/mapa · (tabs)/carteira · (tabs)/notificacoes · (tabs)/perfil
```

`app/(app)/impacto.tsx` entrou na F17 e não foi contada. Consequência prática baixa (a tela funciona e
tem teste — `app/__tests__/impacto.test.tsx`, entre as 18 suítes verdes), mas é a mesma raiz de 6.4.

### 6.15 Sobre a pergunta do escopo: a numeração atrapalha quem chega depois? **Sim.**

Minha avaliação, e discordo de qualquer leitura de que esteja "suficientemente explicada":

O `CLAUDE.md` **explica bem** as duas armadilhas que ele conhece — `f21-` cobrindo duas fases, e o
`git log` mentindo sobre numeração. Essas duas estão resolvidas: há aviso explícito, com o motivo.

O que **não** está explicado é pior, porque é ambiguidade sem aviso:

1. **F16 e F17 designam duas coisas cada** (§6.5), em documentos que são fonte de verdade declarada.
2. **F14–F17 não existem na tabela de fases** (§6.4), mas existem como branch, PR e artefato em disco.
3. A tabela tem **F0–F13, F12b, F12c, F21** — uma sequência com sufixos de letra, um salto de F13 para
   F21, e nenhuma das quatro fases mais recentes.

O teste prático: um leitor que queira saber "qual foi a última coisa entregue?" consulta
`PROGRESSO.md`, lê "F13 — Entrega final — 2026-08-16", e conclui que o projeto parou em agosto. O
`git log` diz outra coisa, e o `CLAUDE.md` proíbe inferir fase do `git log`. **Não há fonte correta
para essa pergunta hoje** — o documento eleito como fonte está incompleto, e o único outro disponível
está explicitamente desautorizado. Isso atende mal a cláusula "organizado, … acompanhado das
informações necessárias para que outra pessoa compreenda o que foi construído".

---

## Discordâncias e não-achados

Registro para que a ausência de achado não pareça descuido:

1. **O item 4 do escopo previa achar órfão e não há.** As 19 classes novas têm 100 % dos métodos
   executados. Os candidatos (1), (2), (5) e (6) são **refutados por medição**, e o (3) tem
   justificativa escrita e defensável. Só o (4) virou achado, e é LACUNA de teste, não órfão.
2. **`integro=true` nunca foi violado, e isso não é elogio nem crítica.** Ele respondeu `true`
   enquanto 500 tokens eram emitidos. Está correto: é outra invariante. Quem quiser prova de
   conservação lê a tabela de §3.2, não o campo `integro`.
3. **O `404` da busca de handle não é vazamento.** É o desenho do ADR 0028, e o comportamento medido
   confirma a indistinguibilidade (handle real de outra tribo → 404, igual a inexistente).
4. **O 400-antes-do-403 em rota ADMIN (§1.6) não é achado.** O schema já é público e nada muda de
   estado. Não inflo o relatório com ele.
5. **A divergência 65→64 do roteiro não é defeito.** Está explicada no próprio documento, e a
   assertion do script compara contra a recompensa congelada da missão — continua sendo teste real.
   O que **é** defeito é o 175 (§2.5), que não fecha com nenhuma execução possível.
6. **Não verifiquei que os testes falham quando deveriam** (§5.9), porque `./mvnw` estava proibido.
   Declaro como não verificado em vez de inferir da cobertura.

---

## Ordem de correção por impacto

**Bloco A — o que um avaliador vê em trinta segundos.** Custo quase zero, impacto máximo, e
`README.md` é o único arquivo tocado.

| # | Correção | Onde | Por quê primeiro |
|---|---|---|---|
| 1 | Gerar as 5 imagens + publicar o vídeo, **ou** remover as referências | `README.md:5,7,13,15` | São as linhas 5 a 15 do documento de entrada. Hoje: cinco imagens quebradas e a string `COLE-A-URL-DO-VIDEO-AQUI` |
| 2 | Corrigir contagem de testes e a citação de data | `README.md:74,78,79` | A citação aponta para um arquivo que diz outra coisa. Medido: **225 testes / 18 suítes** no mobile; **76** classes no backend |
| 3 | Corrigir `V1–V22`/`V900–V904` → `V1–V8, V11–V29` / `V900–V906` | `README.md:311-312` | A faixa errada convida a escolher V23 para uma migration nova, que já existe |
| 4 | Acrescentar `renan@omnitribo.dev` (e `diana`, `erik`) à tabela de usuários | `README.md:404-411` | É a conta que o roteiro usa do começo ao fim |
| 5 | Citar `make demo` na seção de execução | `README.md` | É o preparo oficial da demo e não aparece no documento de entrada |
| 6 | Corrigir "30 decisões" → 33 e "12 documentos" → 14 | `README.md:508-509` | Aritmética; sai no mesmo commit |

**Bloco B — o roteiro, antes de ensaiar.** Estes dois **só fazem sentido juntos**: corrigir o saldo do
resgate sem revisar o bloco anterior reintroduz a inconsistência na próxima medição.

| # | Correção | Onde |
|---|---|---|
| 7 | Reexecutar os blocos `2:00–4:00` e `4:00–5:00` **na mesma sessão** e colar a saída consistente | `ROTEIRO-DEMO.md:132-140` e `:177-182` |
| 8 | No mesmo passe, estender a ressalva de "não decore o número" para cobrir o saldo pós-resgate | `ROTEIRO-DEMO.md:142-148` |

Sem o 8, qualquer variação do multiplicador volta a desalinhar o 7 na próxima medição de clima.

**Bloco C — ponteiros mentirosos no código.** Baratos, e o primeiro é o achado mais afiado do
relatório.

| # | Correção | Onde |
|---|---|---|
| 9 | `DespachanteAlertaPontoLotadoTest` → `DespachanteAlertaOperacionalTest` (`logistica/api`) | `V29__dedup_alerta_operacional.sql:62` |
| 10 | Trocar um dos dois prefixos `eeee1111-` e fazer cada javadoc citar o outro | `IndicePoteImobilizadoTest.java:47-48` **e** `PlanoConsultasQuentesTest.java:53-54` |

O **10 é indivisível**: mudar o prefixo de uma classe e não atualizar o javadoc da outra deixa o
sistema **pior** — o próximo autor lê a lista incompleta da classe não editada e reocupa a faixa
liberada. Migration é imutável, então o 9 precisa de nota de correção no lugar certo (comentário da
migration não se reescreve; ou vai num `COMMENT ON INDEX` ou no javadoc da classe real).

**Bloco D — a documentação de estado.** Maior esforço, e é o que a cláusula "organizado" do enunciado
mede.

| # | Correção | Onde |
|---|---|---|
| 11 | Acrescentar linhas F14, F15, F16, F17 à tabela de fases | `PROGRESSO.md:3-21` |
| 12 | Desambiguar F16 e F17, que hoje designam duas fases cada | `apps/mobile/CLAUDE.md:146` e `PROGRESSO.md:480` |
| 13 | Atualizar F8 de "🟨 Parcial / 2026-08-14" para concluída em 2026-08-20 | `PROGRESSO.md:11` |
| 14 | Registrar F15–F17 nos três `CLAUDE.md`: `ContadorDeQueries`, `perfilador`, `make demo`, `tools/demo/`, `PLANO-B.md`, faixa de sensibilidade, e **12** telas | os três `CLAUDE.md` |

**11 e 12 são o mesmo trabalho e devem sair juntos.** Acrescentar uma linha "F16" à tabela enquanto
`apps/mobile/CLAUDE.md:146` chama outra fase de "F16" cria uma contradição *nova* entre dois
documentos — hoje há uma omissão, e omissão é menos danosa que contradição.

**Bloco E — a rede de proteção.** Único item com código de teste.

| # | Correção | Onde |
|---|---|---|
| 15 | Teste de unidade para os 8 defaults de paginação (5 ramos nunca executados) | os 4 `*FiltroRequest` |
| 16 | Herdado, não desta entrega: cobrir `handleIntegridade` (22001) e `handleConflitoConcorrencia` | `GlobalExceptionHandler.java:203-254` |

**Corrigir a §51-52 de `docs/evidencias/README.md`** (a contradição sobre conservação em ENTREGA) cabe
no Bloco A ou D — é uma linha, e hoje contradiz a tabela do próprio arquivo e a medição de §3.2.

**O que NÃO está nesta lista, de propósito:** a pendência 1 do `CLAUDE.md` (RASCUNHO/ACEITA/EM_DISPUTA
sem porta de ADMIN). Ela é real — apareceu no banco de demonstração durante esta auditoria (§4.3, a
missão `ACEITA` com 42 tokens presos) —, mas muda a máquina de estados de 17 para 20 transições e o
próprio `CLAUDE.md` diz **"Não decida sozinho"**. Não é trabalho de correção de auditoria.

---

**PARE.** Corrigir é tarefa separada, e quem decide quando é o autor do projeto.

---

# Adendo de 2026-09-13 — a cunhagem que sobrou, medida

**Autor:** sessão principal (não o subagente auditor) · **Método:** ciclo completo por HTTP contra o
backend em `dev`, com a soma da conservação lida por `psql` antes e depois.

Este adendo existe porque o item 6 me levou a uma afirmação do `CLAUDE.md` que **não se sustenta**, e
a regra do repositório é medir antes de afirmar. O achado é de DOCUMENTAÇÃO, não de código: o código
declara este comportamento em voz alta; é a documentação de topo que diz o contrário.

## O que o `CLAUDE.md` afirma

> `SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)` é constante **dentro do ciclo de missões**,
> nas quatro categorias — e muda nas DUAS pontas: **sobe** no `APORTE_PATROCINADOR` (emite) e
> **desce** no `RESGATE` (queima). **Nenhuma outra operação a altera**; todas as demais movem token
> de lugar.

A última frase é falsa. Existe uma **terceira** ponta.

## O caminho, no código

`Missao.java:270` dá `FontePote.CUNHAGEM` a toda ENTREGA cuja criação não vem do webhook — isto é,
toda ENTREGA criada por um usuário no app. Daí:

- `MissaoService:615` — `validarPoteSuficienteParaPublicar` **retorna cedo** quando a fonte é
  `CUNHAGEM`: publicar não exige pote;
- `MissaoService:960-974` — `pagaDoPote` é falso, então `debitarPote` **não** é chamado, mas
  `creditoRecompensa.creditarConclusao` credita o executor com a recompensa congelada.

Crédito sem débito em lugar nenhum. O javadoc de `FontePote.CUNHAGEM` é honesto sobre isso — *"É a
última lacuna de cunhagem, e ela está declarada em vez de escondida"* —, e `MissaoService:1039`
repete. Nenhum dos dois é contradito pelo código; o que os contradiz é o `CLAUDE.md` e o README.

## A medição

Ciclo completo: `alice` cria a ENTREGA, publica, `bob` aceita, inicia, faz check-in na coordenada da
origem, `alice` confirma.

```console
$ # criação — o servidor calcula e congela a recompensa
id: c55e8b1a-04de-4221-98a9-ea146f3e0f3b
tokensRecompensa: 22   poteTokens: 0   status: RASCUNHO

$ psql -tAc "SELECT fonte_pote FROM missao WHERE id='c55e8b1a-…'"
CUNHAGEM

$ # publicar com pote ZERO — a guarda é dispensada para CUNHAGEM
-> ABERTA | pote 0

$ # aceitar / iniciar / checkin / confirmar
-> ACEITA | pote 0
-> EM_ANDAMENTO | pote 0
-> AGUARDANDO_CONFIRMACAO | pote 0
-> CONCLUIDA | pote 0
```

Conservação, lida direto do banco:

```console
ANTES:  11330
DEPOIS: 11352
DELTA:  +22          # exatamente a recompensa congelada

saldo de bob:  143 -> 165
```

O ledger, e a ausência de contraparte:

```console
$ psql -c "SELECT sinal, motivo, valor_tokens, contraparte_carteira_id IS NULL AS sem_contraparte
           FROM lancamento WHERE missao_id='c55e8b1a-…'"
  sinal  |      motivo       | valor_tokens | sem_contraparte
---------+-------------------+--------------+-----------------
 CREDITO | RECOMPENSA_MISSAO |           22 | t
```

E a reconciliação, no mesmo instante:

```console
$ curl /api/v1/admin/carteiras/reconciliacao
  integro: True | divergencias: 0
```

## Classificação

**DEFEITO de documentação (alto).** Três afirmações a corrigir, e nenhuma delas é no código:

| # | Afirmação | Onde | Por que é falsa |
|---|---|---|---|
| 1 | *"Nenhuma outra operação a altera"* | `CLAUDE.md`, seção Economia | A conclusão de ENTREGA com `fonte_pote = CUNHAGEM` emite. São **três** pontas, não duas |
| 2 | *"O único ponto de emissão é `APORTE_PATROCINADOR`"* | `CLAUDE.md`, em dois lugares | É o único ponto de emissão **explícito e auditado**. A cunhagem implícita por conclusão continua existindo para um caso |
| 3 | *"uma cunhagem sem lastro em ENTREGA e AJUDA — hoje corrigida, com Δ=0 nas quatro categorias"* | `README.md:482-483` | A correção foi **parcial**: AJUDA passou a pagar do pote (ADR 0025) e a ENTREGA do webhook ganhou patrocinador (ADR 0024). A ENTREGA criada por humano ficou. O "Δ=0 nas quatro categorias" é verdadeiro para os ciclos medidos, e todos usam o **webhook** para ENTREGA |

**Por que nenhuma medição anterior pegou.** `tools/evidencias/conservacao-por-categoria.sh` exercita
ENTREGA **pelo webhook**, que é o caso com patrocinador. A ENTREGA criada no app é a única das cinco
combinações de (categoria × origem) que nenhum ciclo medido cobre — e é justamente a que emite.
`docs/evidencias/README.md` já registrava, no item "o que não está provado", que a conservação em
ENTREGA era a lacuna; o item foi atualizado nesta entrega para dizer **qual** ENTREGA.

**Por que a reconciliação é cega.** Cunhar escreve os dois lados — um lançamento de crédito e o saldo
correspondente —, então ledger e projeção continuam batendo. É o mesmo mecanismo que deixou a
cunhagem de ENTREGA e AJUDA passar por várias fases, e está documentado em
`docs/EVOLUCAO-ARQUITETURAL.md`. A diferença é que agora existe instrumento para a outra invariante
(ADR 0032) — mas ele mede pote **imobilizado**, não emissão.

**O que NÃO estou afirmando.** Que isto seja um bug a corrigir no código. O desenho recusa duas
alternativas por escrito: exigir pote da tribo faria vizinhos custearem logística de varejista
(javadoc de `FontePote`), e não há patrocinador a debitar numa entrega que nenhuma transportadora
reportou. A decisão de fechar a lacuna — ou de declará-la — é do autor, e é candidata a ADR próprio.
O que esta auditoria afirma é só isto: **a documentação de topo diz que a lacuna não existe, e ela
existe, medida.**

## Estado do banco após este adendo

A missão `c55e8b1a-04de-4221-98a9-ea146f3e0f3b` ficou CONCLUIDA no banco de dev e os 22 tokens
emitidos estão em circulação (total 11352). Como o `ROTEIRO-DEMO.md` já exige `make demo` antes de
qualquer ensaio, nada precisa ser desfeito à mão.
