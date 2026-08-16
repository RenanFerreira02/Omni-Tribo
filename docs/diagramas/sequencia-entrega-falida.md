# Sequência — entrega falida → webhook → ponto de custódia → missão → notificação

A tese do produto em forma de fluxo: **uma entrega que falhou vira missão comunitária remunerada.**

Fonte: `HmacWebhookFilter.java`, `WebhookTransportadoraController.java`, `EntregaFalidaService.java`,
`DespachanteAlertaService.java`. Ver [ADR 0020](../adr/0020-ponto-de-custodia-comercial-e-proximidade-por-tribo.md)
e [ADR 0021](../adr/0021-verificacao-de-webhook-de-transportadora.md).

```mermaid
sequenceDiagram
    autonumber
    participant T as 🚚 Transportadora
    participant F as HmacWebhookFilter
    participant C as WebhookController
    participant CL as ConsultaClima<br/>(integracoes)
    participant PR as PrevisorDeRisco<br/>(logistica)
    participant EF as EntregaFalidaService
    participant MS as ConversaoEntregaFalida<br/>(missoes)
    participant DB as PostgreSQL
    participant JOB as DrenadorOutboxJob
    participant DA as DespachanteAlerta<br/>(notificacoes)

    T->>F: POST /webhooks/transportadora<br/>X-Transportadora, X-Timestamp, X-Assinatura

    rect rgb(255, 235, 235)
    Note over F: FORA de qualquer transação
    F->>F: resolve segredo pelo slug (401 se desconhecido)
    F->>F: rate limit por slug (429)
    F->>F: lê e bufferiza o CORPO BRUTO
    F->>F: janela de 5 min do carimbo (401)
    F->>F: HMAC-SHA256(segredo, timestamp + "." + corpo)<br/>comparado em tempo constante (401)
    Note right of F: as quatro causas de 401 são<br/>INDISTINGUÍVEIS no corpo
    F->>C: publica a transportadora VERIFICADA como atributo
    end

    rect rgb(255, 243, 224)
    Note over C,PR: ainda FORA da transação — rede externa aqui é obrigatório
    C->>CL: consultarParaRisco(lat, lon)
    CL-->>C: Optional (cache → disjuntor → bulkhead → retry)
    Note right of CL: NUNCA lança: provedor fora do ar<br/>não pode virar 5xx e fazer a<br/>transportadora reenviar em laço
    C->>PR: prever(contexto de 9 campos)
    PR-->>C: probabilidade, faixa, multiplicador entre 1,00 e 1,50
    end

    rect rgb(232, 244, 238)
    Note over EF,DB: TRANSAÇÃO — locks → sonda → valida → escreve
    C->>EF: registrar(dados, risco)
    EF->>DB: SELECT ponto_custodia ... FOR UPDATE (404 se inativo)
    EF->>DB: sonda (transportadora, codigo_rastreio)
    alt replay
        DB-->>EF: entrega já registrada
        EF-->>T: 200 · mesmo desfecho, replay=true, nada regravado
    else ponto SEM vaga
        EF->>DB: entrega.recusar() + outbox("EntregaFalidaRecusada")
        EF-->>T: 200 · desfecho RECUSADA, missaoId=null
        Note right of EF: 200, não 4xx — devolver erro faria a<br/>transportadora reenviar contra um ponto<br/>que continuará lotado
    else ponto COM vaga
        EF->>DB: ponto.registrarEntrada() (ocupação++)
        EF->>DB: congela risco na linha (score, faixa, multiplicador, versão)
        EF->>MS: abrirMissaoDeRetirada(...)
        MS->>DB: missao RASCUNHO → PUBLICAR → ABERTA<br/>criador = usuário-sistema · recompensa congelada
        MS-->>EF: missaoId
        EF->>DB: vincularMissao + outbox("EntregaFalidaConvertida")
        EF-->>T: 200 · desfecho CONVERTIDA, missaoId
    end
    end

    rect rgb(240, 240, 250)
    Note over JOB,DA: TRANSAÇÃO SEPARADA, assíncrona
    JOB->>DB: busca pendentes FOR UPDATE SKIP LOCKED
    JOB->>DA: anunciarMissaoDeRetirada(payload com faixaRisco)
    DA->>DB: tribosNoRaio (PostGIS) — fan-out por TRIBO, não por usuário
    DA->>DB: usuários com consentimento NOTIFICACAO **e** LOCALIZACAO
    DA->>DB: filtra por nível mínimo (reputação)
    DA->>DB: dedup por (usuário, tipo, missão) ANTES do teto
    DA->>DB: teto por hora — com carve-out para risco ALTO
    DA->>DB: INSERT alerta (prioridade derivada da faixa)
    end
```

## Cinco decisões que o desenho torna visíveis

**O HMAC é sobre o corpo BRUTO, não sobre o objeto desserializado.** Assinar o objeto reserializado
compararia uma representação nossa, não a que a transportadora assinou — qualquer diferença de
ordem de campos ou de formatação quebraria a verificação, ou pior, a faria passar quando não devia.

**O carimbo entra DENTRO do material assinado.** Se ficasse de fora, um atacante poderia trocar o
`X-Timestamp` de uma requisição capturada e reusar a assinatura para sempre.

**O risco é calculado ANTES da transação abrir.** Se fosse calculado dentro, o `FOR UPDATE` do ponto
de custódia ficaria segurado durante uma chamada de rede externa — e um provedor lento viraria
contenção no banco.

**A recusa por lotação responde 200.** Não é erro HTTP; é desfecho de negócio, registrado e
anunciado como qualquer outro. Foi o que a execução de 2026-08-16 confirmou: a recusa gerou linha em
`entrega_falida`, evento na outbox e alerta `PONTO_CUSTODIA_LOTADO`
([evidência](../evidencias/f13-execucao-do-zero.md)).

**O fan-out é por TRIBO, não por usuário.** A tabela `usuario` não tem coluna geográfica; quem tem
posição é a tribo. Como a decisão de notificar usou a *posição* da pessoa, o consentimento exigido é
duplo: `NOTIFICACAO` **e** `LOCALIZACAO`.
