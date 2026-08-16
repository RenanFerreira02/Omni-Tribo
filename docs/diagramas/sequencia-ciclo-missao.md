# Sequência — aceitar → iniciar → check-in → confirmar → pote pago

Fonte: `MissaoService.java` (`aplicar` linhas 941–993, `registrarCheckin` 599–693,
`concluirComCredito` 813–918) e `MissaoController.java`.

O ponto do diagrama é mostrar **o que acontece dentro de uma transação e o que fica fora dela** — é
onde estão as decisões que a banca vai perguntar.

```mermaid
sequenceDiagram
    autonumber
    actor EX as Executor
    actor CR as Criador
    participant API as MissaoController
    participant MS as MissaoService
    participant GEO as RegistroCheckin<br/>(geolocalizacao)
    participant CAR as CreditoRecompensa<br/>(carteira)
    participant ID as ProgressaoUsuario<br/>(identidade)
    participant LOG as BaixaCustodia<br/>(logistica)
    participant OBX as PublicadorEventos<br/>(outbox)
    participant DB as PostgreSQL
    participant JOB as DrenadorOutboxJob

    rect rgb(232, 244, 238)
    Note over API,DB: TRANSAÇÃO 1 — aceitar
    EX->>API: POST /missoes/{id}/aceitar
    API->>MS: aceitar
    MS->>DB: SELECT ... FOR UPDATE ①
    MS->>MS: 403 autorização → 409 transição
    MS->>MS: valida nível mínimo (reputação)
    MS->>DB: UPDATE missao (executor_id, status=ACEITA)
    Note right of MS: aceitar NÃO credita nada
    end

    rect rgb(232, 244, 238)
    Note over API,DB: TRANSAÇÃO 2 — iniciar
    EX->>API: POST /missoes/{id}/iniciar
    MS->>DB: SELECT ... FOR UPDATE → status=EM_ANDAMENTO
    end

    rect rgb(232, 244, 238)
    Note over API,DB: TRANSAÇÃO 3 — check-in
    EX->>API: POST /missoes/{id}/checkin {lat, lon, acuraciaM, mocked}
    MS->>DB: SELECT ... FOR UPDATE ①
    MS->>MS: 403 autorização
    MS->>GEO: consultar(chave de idempotência)
    alt replay
        GEO-->>MS: check-in anterior → devolve o mesmo resultado
    else primeiro
        MS->>MS: 409 transição
        MS->>GEO: registrar(coordenada)
        GEO->>DB: ST_Distance no servidor · grava linha em checkin
        alt aceito
            GEO-->>MS: válido → status=AGUARDANDO_CONFIRMACAO
        else rejeitado
            GEO-->>MS: ResultadoRejeitado (VALOR, não exceção)
            Note over API: o 422 é lançado DEPOIS do commit,<br/>para preservar a linha gravada
        end
    end
    end

    rect rgb(255, 243, 224)
    Note over API,OBX: TRANSAÇÃO 4 — confirmar (a única que credita)
    CR->>API: POST /missoes/{id}/confirmar
    API->>MS: concluirComCredito
    MS->>DB: SELECT ... FOR UPDATE ① (primeira leitura)
    MS->>MS: ② 403 autorização
    MS->>DB: ③ sonda replay pela chave idempotente
    MS->>MS: ④ 409 transição · ⑤ guarda "sem executor"
    opt categoria TRIBO ou COLETA
        MS->>DB: ⑥ debitarPote(tokens)
    end
    MS->>CAR: ⑦ creditarConclusao(tokens)
    CAR->>DB: INSERT lancamento (append-only) + UPDATE saldo
    MS->>ID: ⑧ concederXp(xp)
    MS->>DB: ⑨ UPDATE status=CONCLUIDA + INSERT missao_evento
    MS->>LOG: ⑩ darBaixa (SÍNCRONA, não outbox)
    MS->>OBX: ⑪ publicar("MissaoConcluida")
    OBX->>DB: INSERT outbox
    Note over MS,OBX: tudo isto commita JUNTO ou nada commita
    end

    JOB->>DB: SELECT ... FOR UPDATE SKIP LOCKED (transação separada)
    JOB->>JOB: despacha alerta · backoff 30s→1m→2m→4m→8m
```

## Por que cada decisão está onde está

**① `FOR UPDATE` é sempre a primeira leitura da transação.** Não é estilo: se outra consulta abrisse
a transação, duas conclusões concorrentes poderiam adquirir os locks em ordens diferentes e
travar uma na outra.

**A ordem 403 → 409 é obrigatória** (`MissaoStateMachine`, javadoc linhas 60–67): responder 409
antes de 403 diria a quem não tem permissão em que estado a missão está. O check-in é a única
exceção — insere a sondagem de idempotência entre os dois, para que um replay devolva o resultado
anterior em vez de um 409 enganoso.

**⑥ O débito do pote só acontece para TRIBO e COLETA.** ENTREGA e AJUDA **cunham** — é a Pendência
#1, medida em [`../evidencias/f13-conservacao-por-categoria.md`](../evidencias/f13-conservacao-por-categoria.md)
e contada por inteiro em [`../EVOLUCAO-ARQUITETURAL.md`](../EVOLUCAO-ARQUITETURAL.md).

**⑩ A baixa de custódia é SÍNCRONA, e é a única chamada de outro módulo que não passa pela outbox.**
A outbox é *at-least-once*: um redespacho liberaria uma vaga que já foi liberada, e a ocupação do
ponto viraria mentira. Liberar vaga precisa acontecer **exatamente uma vez**, então acompanha a
transação.

**⑪ A publicação na outbox é o último passo e roda na MESMA transação.** Se a conclusão der rollback,
o anúncio não sobrevive; se commitar, o anúncio está durável e o drenador o entrega com retry. É o
padrão outbox no seu propósito original — atomicidade entre mudar estado e anunciar a mudança, sem
broker.

**O `PublicadorOutbox.publicar` é `MANDATORY`, não `REQUIRED`.** Chamado fora de transação, ele
falha alto em vez de abrir uma própria — abrir uma reintroduziria exatamente a não-atomicidade que o
padrão existe para eliminar.

> **`REQUIRES_NEW` é proibido neste caminho.** A transação externa segura o `FOR UPDATE` e a interna
> pediria uma segunda conexão: com N ≥ tamanho do pool, deadlock de pool e 500 para todo mundo. Não é
> teoria — aconteceu no check-in da F6 e derrubava até o login.
