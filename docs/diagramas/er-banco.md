# ER do banco

14 tabelas de negócio, schema aplicado por Flyway (`V1`–`V22`), seed em faixa separada `V900+`.

**Linha cheia = foreign key real. Linha pontilhada = referência por UUID puro, deliberadamente
SEM foreign key** — é a fronteira de módulo materializada no schema.

```mermaid
erDiagram
    TRIBO ||--o{ USUARIO : "agrupa"
    TRIBO ||--o{ PONTO_CUSTODIA : "abriga"

    USUARIO ||--o{ CONSENTIMENTO : "concede"
    USUARIO ||--o{ REFRESH_TOKEN : "possui"
    USUARIO ||--o{ DISPOSITIVO : "registra"
    USUARIO ||--|| CARTEIRA : "tem uma"
    USUARIO ||--o{ MISSAO : "cria"
    USUARIO ||--o{ MISSAO_EVENTO : "atua em"
    USUARIO ||--o{ CHECKIN : "registra"
    USUARIO ||--o{ ALERTA : "recebe"

    MISSAO ||--o{ MISSAO_EVENTO : "trilha"
    CARTEIRA ||--o{ LANCAMENTO : "ledger"

    ENTREGA_FALIDA }o--|| PONTO_CUSTODIA : "ocupa vaga"

    MISSAO ||..o{ CHECKIN : "geolocalizacao → missoes"
    MISSAO ||..o{ LANCAMENTO : "carteira → missoes"
    MISSAO ||..o| ENTREGA_FALIDA : "logistica → missoes"
    MISSAO ||..o{ ALERTA : "compartilhado → missoes"
    PONTO_CUSTODIA ||..o{ MISSAO : "missoes → logistica"

    TRIBO {
        uuid id PK
        varchar nome
        varchar bairro
    }
    USUARIO {
        uuid id PK
        varchar email UK
        varchar handle UK
        uuid tribo_id FK
        int xp "monotônico"
        int nivel "cache derivado do xp"
        varchar papel "CHECK USUARIO|ADMIN"
        varchar status "CHECK ATIVO|INATIVO|SUSPENSO|BANIDO"
        timestamptz anonimizado_em "LGPD"
        int versao "lock otimista"
    }
    MISSAO {
        uuid id PK
        uuid criador_id FK
        uuid executor_id FK
        uuid ponto_custodia_id "UUID puro, sem FK"
        varchar categoria "ENTREGA|COLETA|TRIBO|AJUDA"
        varchar status "9 estados"
        int xp_recompensa "congelado na criação"
        bigint tokens_recompensa "congelado na criação"
        numeric valor_brl "ck_missao_economia: sempre 0"
        bigint pote_tokens "financiado pela tribo"
        geography origem "POINT,4326"
        geography destino "POINT,4326"
        int raio_checkin_m
        varchar complexidade
        int versao_formula
        numeric multiplicador_risco "[1,00; 1,50]"
        varchar faixa_risco "BAIXO|MEDIO|ALTO"
        int nivel_minimo
        timestamptz estado_desde "marco da varredura"
    }
    MISSAO_EVENTO {
        uuid id PK
        uuid missao_id FK
        uuid ator_id FK
        varchar tipo
        varchar de_status
        varchar para_status
        jsonb payload
    }
    CHECKIN {
        uuid id PK
        uuid missao_id "UUID puro, sem FK"
        uuid usuario_id FK
        geography ponto "POINT,4326"
        numeric acuracia_m
        numeric distancia_alvo_m "calculada pelo PostGIS"
        boolean mock_detectado
        boolean valido
        varchar codigo_rejeicao
        varchar chave_idempotencia UK "sha256(usuario|missao|chave)"
    }
    CARTEIRA {
        uuid id PK
        uuid usuario_id FK "UNIQUE"
        numeric saldo_brl "não negativo"
        bigint saldo_tokens "não negativo"
        int versao
    }
    LANCAMENTO {
        uuid id PK
        uuid carteira_id FK
        uuid contraparte_carteira_id FK
        uuid missao_id "UUID puro, sem FK"
        varchar sinal "CREDITO|DEBITO"
        varchar motivo "RECOMPENSA_MISSAO|FINANCIAMENTO_TRIBO|ESTORNO|..."
        bigint valor_tokens
        varchar chave_idempotencia UK
        bigint saldo_apos_tokens "snapshot"
    }
    PONTO_CUSTODIA {
        uuid id PK
        varchar codigo UK
        uuid tribo_id FK
        varchar tipo "LOJA|LOCKER|PORTARIA|VIZINHO"
        geography ponto "POINT,4326"
        int capacidade
        int ocupacao
        boolean ativo
    }
    ENTREGA_FALIDA {
        uuid id PK
        varchar transportadora
        varchar codigo_rastreio "UNIQUE(transportadora, rastreio)"
        uuid ponto_custodia_id FK
        uuid missao_id "UUID puro, sem FK"
        numeric risco_probabilidade "congelado"
        varchar risco_faixa
        numeric risco_multiplicador
        int risco_versao_modelo
        timestamptz recusada_em "lotação: sem missão"
    }
    OUTBOX {
        uuid id PK
        varchar tipo_evento
        uuid agregado_id
        jsonb payload
        timestamptz publicado_em
        int tentativas
        timestamptz proxima_tentativa_em "backoff exponencial"
    }
    ALERTA {
        uuid id PK
        uuid usuario_id FK "NULL = global"
        uuid missao_id "UUID puro, sem FK"
        varchar tipo
        smallint prioridade "0..2, ordenável"
        boolean lido
    }
    CONSENTIMENTO {
        uuid id PK
        uuid usuario_id FK
        varchar tipo "LOCALIZACAO|NOTIFICACAO|TERMOS"
        boolean concedido
        varchar versao_texto
    }
    REFRESH_TOKEN {
        uuid id PK
        uuid usuario_id FK
        varchar token_hash
        uuid familia_id
        uuid substituido_por "sem FK: evita ciclo"
        timestamptz revogado_em
    }
    DISPOSITIVO {
        uuid id PK
        uuid usuario_id FK
        varchar push_token UK
        varchar plataforma "IOS|ANDROID"
    }
    AUDITORIA {
        uuid id PK
        uuid ator_id "NULL = anônimo"
        varchar acao
        varchar entidade
        varchar correlation_id "64 chars: cabe traceparent W3C"
    }
```

> `AUDITORIA` e `OUTBOX` aparecem sem aresta de propósito: a primeira registra ação sobre qualquer
> entidade e não tem FK para nenhuma; a segunda guarda `agregado_id` genérico.

## As seis referências sem FK

| Coluna | Fronteira | Por quê |
|---|---|---|
| `missao.ponto_custodia_id` | `missoes` → `logistica` | |
| `checkin.missao_id` | `geolocalizacao` → `missoes` | |
| `lancamento.missao_id` | `carteira` → `missoes` | |
| `entrega_falida.missao_id` | `logistica` → `missoes` | |
| `alerta.missao_id` | `compartilhado` → `missoes` | |
| `refresh_token.substituido_por` | auto-referência em `identidade` | evita constraint circular na rotação |

As cinco primeiras existem pela mesma razão, documentada nas próprias migrations: **viabilizar a
extração futura de cada módulo em serviço independente sem quebrar o schema de quem referencia.** É
a fronteira do ArchUnit descendo até o banco — de nada adiantaria proibir o import em Java e manter
uma FK que impede separar as tabelas.

O preço é explícito: **não há integridade referencial nessas colunas**. Quem apaga precisa saber o
que está apagando, e é por isso que as tabelas relevantes são append-only.

## Três tabelas append-only

`lancamento`, `auditoria` e `checkin` são **append-only**, com `REVOKE UPDATE, DELETE` para o papel
da aplicação. Correção se faz por **estorno**, nunca por `UPDATE`.

O `REVOKE` já esteve **inerte**: a aplicação conectava como dono das tabelas, e dono ignora `REVOKE`.
Hoje ela conecta como `omnitribo_app`, o Flyway tem credencial própria com DDL
([ADR 0017](../adr/0017-papeis-de-banco-separados.md)), e
`MigracaoTest.aplicacao_nao_consegue_apagar_nem_alterar_o_ledger_em_runtime` prova em runtime
(SQLState 42501) — não mais lendo o catálogo.

## Convenções de tipo

| Dado | Tipo | Nunca |
|---|---|---|
| Dinheiro | `numeric(12,2)` → `BigDecimal` | `double`, `String` |
| Tokens | `bigint` | |
| Coordenada | `geography(POINT,4326)` | par de `double`; distância **nunca** é armazenada |
| Data/hora | `timestamptz` | `timestamp` |
| Enum | `varchar` + `CHECK` + `EnumType.STRING` | ordinal |
