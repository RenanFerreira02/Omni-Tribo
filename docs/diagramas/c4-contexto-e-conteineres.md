# C4 — contexto e contêineres

**Isto é o que FOI construído**, não o que se pretende construir. A visão de produção em escala está
em [`arquitetura-alvo.md`](arquitetura-alvo.md), separada de propósito para que as duas nunca sejam
confundidas numa banca.

Desenhado com `flowchart` em vez do `C4Context` do Mermaid: o modo C4 ainda é experimental e falha
de renderização em documento de entrega é risco desnecessário.

---

## Nível 1 — Contexto

```mermaid
flowchart TB
    morador["👤 Morador<br/><i>aceita missões, faz check-in,<br/>recebe XP e tokens</i>"]
    admin["👤 Administrador<br/><i>resolve disputas, destrava<br/>missões, lê reconciliação</i>"]

    subgraph limite [" "]
        omni["<b>Omni-Tribo</b><br/>Missões sociais hiperlocais<br/>gamificadas + conversão de<br/>entrega falida em missão"]
    end

    transportadora["🚚 Transportadora<br/><i>sistema externo</i><br/>anuncia entrega falida<br/>via webhook HMAC"]
    meteo["🌦️ Open-Meteo<br/><i>sistema externo</i><br/>clima do destino"]
    viacep["📮 ViaCEP<br/><i>sistema externo</i><br/>endereço por CEP"]
    osm["🗺️ OpenStreetMap<br/><i>sistema externo</i><br/>tiles do mapa"]

    morador -->|"usa (HTTPS/JSON)"| omni
    admin -->|"administra"| omni
    transportadora -->|"POST assinado<br/>HMAC-SHA256"| omni
    omni -->|"consulta (falha = 503,<br/>recurso some da UI)"| meteo
    omni -->|"consulta"| viacep
    morador -.->|"carrega tiles<br/>direto no WebView"| osm

    style omni fill:#1f6f4a,stroke:#0d3b26,color:#fff
    style limite fill:none,stroke:#1f6f4a,stroke-dasharray: 5 5
```

**Por que os tiles do OpenStreetMap saem do aparelho e não da API:** o mapa é um WebView com Leaflet
([ADR 0012](../adr/0012-mapa-por-webview-e-leaflet.md)). Proxiar tile pela nossa API não traria
benefício e criaria um caminho de banda que nada exige.

**Clima e CEP, ao contrário, são proxiados** — o app nunca fala com Open-Meteo ou ViaCEP direto
([ADR 0011](../adr/0011-dependencias-externas-e-anonimizacao.md)). É o que permite cache, disjuntor
e resposta 503 uniforme quando o provedor cai.

---

## Nível 2 — Contêineres

```mermaid
flowchart TB
    morador["👤 Morador"]
    transportadora["🚚 Transportadora"]

    subgraph sistema ["Omni-Tribo — tudo em uma máquina de desenvolvimento"]
        direction TB
        app["<b>App mobile</b><br/>Expo SDK 57 · React Native · TypeScript strict<br/>Expo Router · TanStack Query · Zustand<br/><i>roda no Expo Go — sem build nativo</i>"]

        subgraph api ["<b>API</b> — Spring Boot 4.1 · Java 21 · monólito modular"]
            direction LR
            identidade["identidade"]
            missoes["missoes"]
            geo["geolocalizacao"]
            carteira["carteira"]
            logistica["logistica"]
            notificacoes["notificacoes"]
            integracoes["integracoes"]
            compartilhado["compartilhado"]
        end

        banco[("<b>PostgreSQL 16 + PostGIS 3.5</b><br/>schema por Flyway (V1–V22)<br/>seed em faixa V900+<br/><i>container Docker/podman</i>")]
    end

    meteo["🌦️ Open-Meteo"]
    viacep["📮 ViaCEP"]

    morador -->|"HTTP :8080<br/>JWT Bearer"| app
    app -->|"REST /api/v1<br/>JSON"| api
    transportadora -->|"POST /webhooks/transportadora<br/>HMAC sobre o corpo bruto"| api
    api -->|"JDBC · papel omnitribo_app<br/>(sem UPDATE/DELETE no ledger)"| banco
    api -->|"cache → disjuntor →<br/>bulkhead → retry"| meteo
    api -->|"idem"| viacep

    style api fill:#e8f4ee,stroke:#1f6f4a
    style banco fill:#dbe9f5,stroke:#2b5f8f
    style app fill:#f3ecf8,stroke:#6b4d8f
```

### Portas de rede

| Porta | O quê | Observação |
|---|---|---|
| `8080` | API | Swagger UI em `/swagger-ui.html` no perfil `dev` |
| `8090` | Actuator | Cadeia de segurança **própria**: `health` e `info` anônimos, `metrics` autenticado |
| `5432` | PostgreSQL | container |
| `8081` | Metro/Expo | **é por isso que o actuator não está em 8081** — os dois juntos davam `BindException` e derrubavam a aplicação inteira |

### Os oito módulos

Um pacote por módulo, cada um com `api/` (controllers, DTOs, portas), `dominio/` (entidades, regras)
e `infra/` (repositórios, clientes). **Módulo só acessa outro por `api/` pública ou por evento** —
nunca repositório nem entidade JPA alheia. A regra é verificada por ArchUnit
(`RegrasArquiteturaTest`) sobre os 7 módulos de negócio; `compartilhado` é shared por design e tem
regra própria, mais restritiva, para `compartilhado/infra`
([ADR 0018](../adr/0018-fronteira-do-compartilhado.md)).

Essa fronteira é o que torna a decomposição da [arquitetura-alvo](arquitetura-alvo.md) um trabalho de
extração, e não de reescrita — e é a mesma razão pela qual seis referências entre módulos são **UUID
puro sem FK** no banco (ver [`er-banco.md`](er-banco.md)).
