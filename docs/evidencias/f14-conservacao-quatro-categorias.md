# Conservação do TOKEN nas quatro categorias — e o que a reconciliação continua não vendo

**Data:** 2026-08-22 (reexecutada após o ADR 0026) · **Fase:** F14 · **Ambiente:** banco recriado do zero (`make reset`), backend
no perfil `dev`, PostgreSQL 16.9 + PostGIS 3.5 em container (podman).

Esta evidência substitui [`f13-conservacao-por-categoria.md`](./f13-conservacao-por-categoria.md),
que media a economia quando **duas** das quatro categorias cunhavam token. Depois do
[ADR 0024](../adr/0024-carteira-de-patrocinador.md) (carteira de patrocinador) e do
[ADR 0025](../adr/0025-ajuda-paga-do-pote.md) (AJUDA), toda missão com financiador paga o executor
**do pote**, e a afirmação a defender mudou de tamanho:

> `SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)` é constante no ciclo de missões das
> **quatro** categorias. Só um aporte de patrocinador altera essa soma.

## As duas invariantes, e por que a distinção continua sendo o ponto

| | Definição | Tem endpoint? |
|---|---|---|
| **Reconciliação** | para cada carteira, `saldo` == `SUM(lançamentos)` | **Sim** — `GET /api/v1/admin/carteiras/reconciliacao` |
| **Conservação** | `SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)` constante no ciclo | **Não** |

Cunhar token escreve os **dois lados** — um lançamento de crédito e o saldo correspondente —, então a
reconciliação continua respondendo `integro=true`. Foi exatamente assim que a cunhagem de ENTREGA e
AJUDA passou despercebida por várias fases. Nesta medição a reconciliação é consultada em **todos**
os pontos, e responde `integro=true` sempre: ela não é a prova da conservação, é a demonstração de
que não seria capaz de ser.

## Método

Cinco ciclos, todos por HTTP contra o servidor de pé. Quem financia **nunca** é o criador — o
ADR 0009 mantém "quem cria a missão não paga", e é essa separação que os ciclos 1 a 3 exercitam.

| Ciclo | Categoria | Quem faz o quê | Fonte do pote |
|---|---|---|---|
| 1 | TRIBO | bob cria · carol financia · alice executa | `COMUNIDADE` |
| 2 | COLETA | carol cria · bob financia · alice executa | `COMUNIDADE` |
| 3 | AJUDA | bob cria · carol financia · alice executa | `COMUNIDADE` |
| 4 | ENTREGA | webhook HMAC de `transportadora-dev` · alice executa · **transportadora confirma** | `PATROCINADOR` |
| 5 | ENTREGA | webhook de `transportadora-sem-saldo`, sem aporte | — (recusa) |

O ciclo 4 é a ENTREGA que **tem** financiador: a de entrega falida, em que o patrocinador da
transportadora é debitado na mesma transação que cria a missão. ENTREGA criada por um usuário no app
não entra aqui — ver "o que isto não prova".

## Comandos que geraram a saída

```bash
# 1. Banco do zero, com os seeds (inclui o patrocinador de transportadora-dev, V905)
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock   # só em máquina com podman
make reset

# 2. Backend em dev. Sem override nenhum desde o ADR 0026: o ciclo 4 fecha pela confirmação da
#    transportadora, por HTTP. Antes dele a evidência precisava acelerar a varredura e recuar
#    `estado_desde` por SQL, porque nenhum humano confirma missão do usuário-sistema.
cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 3. A medição
bash tools/evidencias/conservacao-por-categoria.sh
```

Script: [`../../tools/evidencias/conservacao-por-categoria.sh`](../../tools/evidencias/conservacao-por-categoria.sh)

## Saída real

```
############ LOGINS ############
ALICE ok
BOB ok
CAROL ok
ADMIN ok

############ BASELINE ############
carteiras|potes|total = 10689|156|10845
reconciliação inicial: {"integro":true,"divergencias":0}

############ CICLO 1 — TRIBO (bob cria, carol financia, alice executa) ############
prévia: 38 tokens, 114 XP
criada: 60cd5a92-49bb-44b7-9554-c31da2881e58 status=RASCUNHO pote=0
financiado: {"poteTokens":38,"saldoTokensRestante":34}
    ✓ financiar não cria nem destrói: 10845
publicar: ABERTA
aceitar:  ACEITA
iniciar:  EM_ANDAMENTO
checkin:  AGUARDANDO_CONFIRMACAO
confirmar:CONCLUIDA
--> conservação antes=10845 depois=10845 Δ=0  (recompensa: 38)
    ✓ Δ do ciclo TRIBO: 0
--> reconciliação: {"integro":true,"divergencias":0}

############ CICLO 2 — COLETA (carol cria, bob financia, alice executa) ############
prévia: 35 tokens, 105 XP
criada: 6e5c05c6-c0bc-4051-b87e-3a8c395fa021 status=RASCUNHO pote=0
financiado: {"poteTokens":35,"saldoTokensRestante":143}
    ✓ financiar não cria nem destrói: 10845
publicar: ABERTA
aceitar:  ACEITA
iniciar:  EM_ANDAMENTO
checkin:  AGUARDANDO_CONFIRMACAO
confirmar:CONCLUIDA
--> conservação antes=10845 depois=10845 Δ=0  (recompensa: 35)
    ✓ Δ do ciclo COLETA: 0
--> reconciliação: {"integro":true,"divergencias":0}

############ CICLO 3 — AJUDA (bob cria, carol financia, alice executa) ############
prévia: 30 tokens, 90 XP
criada: b2e2ca31-599f-4837-b441-c68cf43966f9 status=RASCUNHO pote=0
financiado: {"poteTokens":30,"saldoTokensRestante":4}
    ✓ financiar não cria nem destrói: 10845
publicar: ABERTA
aceitar:  ACEITA
iniciar:  EM_ANDAMENTO
checkin:  AGUARDANDO_CONFIRMACAO
confirmar:CONCLUIDA
--> conservação antes=10845 depois=10845 Δ=0  (recompensa: 30)
    ✓ Δ do ciclo AJUDA: 0
--> reconciliação: {"integro":true,"divergencias":0}

############ CICLO 4 — ENTREGA via webhook (patrocinador financia o pote) ############
saldo do patrocinador antes: 5000
webhook: {"desfecho":"CONVERTIDA","missaoId":"00f55b94-98e5-4bb4-908c-03c8fe92eb1c","replay":false}
    ✓ desfecho: CONVERTIDA
missão: fonte_pote|recompensa|pote = PATROCINADOR|66|66
    ✓ fonte do pote: PATROCINADOR
    ✓ pote cobre a recompensa: 66
    ✓ converter não cria nem destrói: 10845
aceitar:  ACEITA
iniciar:  EM_ANDAMENTO
checkin:  AGUARDANDO_CONFIRMACAO
confirmação: {"tokensCreditados":66,"replay":false}
    ✓ conclusão por confirmação da transportadora: CONCLUIDA
saldo do patrocinador depois: 4934  (pagou 66)
--> conservação antes=10845 depois=10845 Δ=0  (recompensa: 66)
    ✓ Δ do ciclo ENTREGA: 0
--> reconciliação: {"integro":true,"divergencias":0}

############ CICLO 5 — ENTREGA sem patrocínio (esperado: 200, sem missão) ############
patrocinador cadastrado: {"transportadoraSlug":"transportadora-sem-saldo","ativo":true}
    ✓ nasce sem saldo: 0
webhook: {"desfecho":"SEM_PATROCINIO","missaoId":null,"mensagem":"Sem patrocínio ativo para esta transportadora: a ocorrência foi registrada e nenhuma missão foi criada. Reenviar não altera o resultado."}
    ✓ HTTP: 200
    ✓ desfecho: SEM_PATROCINIO
    ✓ sem missão no corpo: null
    ✓ nenhuma missão criada: 24
    ✓ nenhum token cunhado: 10845
--> reconciliação: {"integro":true,"divergencias":0}

############ RESUMO ############
TRIBO    Δ=0  recompensa=38
COLETA   Δ=0  recompensa=35
AJUDA    Δ=0  recompensa=30
ENTREGA  Δ=0  recompensa=66  (pote pago pelo patrocinador)
conservação: baseline=10845  final=10845
reconciliação final: {"integro":true,"divergencias":0}
lançamentos por motivo:
APORTE_PATROCINADOR = 2
BONUS = 4
FINANCIAMENTO_PATROCINADOR = 1
FINANCIAMENTO_TRIBO = 7
RECOMPENSA_MISSAO = 12
missões por fonte_pote:
COMUNIDADE = 13
CUNHAGEM = 10
PATROCINADOR = 1

Todas as conferências passaram.
```

## Leitura

- **Δ = 0 nas quatro categorias.** A soma sai de `10845` e volta a `10845` depois de cada ciclo, e a
  medição intermediária mostra o token mudando de lugar sem mudar de quantidade: no ciclo 1, carol
  vai de 72 para 34 tokens e o pote da missão recebe 38.
- **O ciclo 4 fecha o ciclo inteiro por HTTP**, incluindo a confirmação da transportadora: a missão
  vai a CONCLUIDA no mesmo minuto, sem nenhum `UPDATE` manual e sem esperar as 72 h da varredura.
- **O ciclo 4 mostra a tese do produto fechando o caixa.** O patrocinador sai de `5000` para `4934`
  — pagou exatamente os 66 tokens da recompensa —, e quem recebeu foi a vizinha que buscou a
  encomenda. Nenhum token foi criado para isso acontecer.
- **`missões por fonte_pote` fecha a leitura:** `COMUNIDADE = 13`, `PATROCINADOR = 1`,
  `CUNHAGEM = 10`. As dez de CUNHAGEM são históricas — missões semeadas ou criadas quando a regra
  era outra; a coluna é congelada na criação justamente para não reescrever o passado.
- **`APORTE_PATROCINADOR = 2`** são os dois aportes de seed. É o único motivo do ledger que emite
  token, e é por isso que ele mora num endpoint ADMIN, auditado e idempotente.

## O que isto NÃO prova

- **Não prova nada sobre ENTREGA criada por humano.** Ela continua `FontePote.CUNHAGEM` e continua
  cunhando na conclusão, porque não tem transportadora e portanto não tem patrocinador a debitar. É
  a última lacuna de cunhagem do sistema, e está declarada na linha da missão — não escondida num
  `if`. Ver ADR 0024 §8.
- **Não prova conservação sob concorrência.** Estes cinco ciclos são sequenciais, um usuário por vez.
  Quem cobre corrida por saldo é a suíte (`ConclusaoConcorrenteTest`, `CarteiraConcorrenteTest`,
  `TransferenciaDeadlockTest`, `PatrocinadorAdminTest`), com 100 threads e asserções de ledger.
- **Não prova nada sobre a varredura de prazo.** O ciclo 4 fecha pela confirmação da transportadora
  (ADR 0026); `EXPIRAR_CONFIRMACAO` continua existindo como rede de segurança e **não é exercitado
  aqui**. Quem o cobre é `ExpiracaoMissoesServiceTest` e o ciclo de expiração de
  `FinanciamentoControllerTest`.
- **`integro=true` não é prova de conservação.** É a afirmação central deste documento e vale
  repetir: a reconciliação compara projeção contra ledger, e cunhar escreve os dois. Ela responderia
  `integro=true` mesmo com token sendo criado do nada — foi o que fez durante várias fases.
- **Não prova que o saldo do patrocinador é sustentável.** O aporte é uma decisão comercial de quem
  patrocina, não um valor derivado da encomenda; `valor_ofertado_brl` fica gravado como registro e
  nunca é convertido em token (ADR 0024 §2b). Se o saldo acabar, o desfecho é o do ciclo 5 — e a
  encomenda simplesmente não vira missão.
