# Arquitetura-alvo em escala

> # ⚠️ VISÃO DE PRODUÇÃO — NÃO IMPLEMENTADA NESTE CICLO
>
> **Nada nesta página existe no repositório.** O que foi construído está em
> [`c4-contexto-e-conteineres.md`](c4-contexto-e-conteineres.md): uma API, um banco, um app, tudo
> numa máquina de desenvolvimento.
>
> Esta página existe para responder "e se precisasse escalar?" sem que a resposta contamine a
> descrição do que é real. Os dois desenhos ficam em arquivos separados exatamente por isso.

---

```mermaid
flowchart TB
    users["👥 Usuários"]
    carriers["🚚 Transportadoras"]

    subgraph borda ["BORDA — não implementada"]
        dns["DNS + CDN"]
        waf["WAF · TLS termination<br/>rate limit de borda · DDoS"]
        lb["Balanceador L7<br/>health check em /actuator/health"]
    end

    subgraph app ["APLICAÇÃO — N instâncias sem estado"]
        api1["API · instância 1"]
        api2["API · instância 2"]
        api3["API · instância N"]
    end

    subgraph async ["ASSÍNCRONO — não implementado"]
        broker[["Broker de mensageria<br/>(substitui a tabela outbox<br/>como transporte)"]]
        workers["Workers de notificação<br/>escalados à parte"]
        push["Push nativo<br/>APNs · FCM"]
    end

    subgraph dados ["DADOS"]
        primary[("PostgreSQL + PostGIS<br/><b>primário</b>")]
        replica[("Réplica de leitura<br/>radar geoespacial")]
        cache[("Cache distribuído<br/>substitui o Caffeine local")]
        primary -.->|"replicação"| replica
    end

    subgraph obs ["OBSERVABILIDADE — não implementada"]
        metrics["Métricas + alertas"]
        traces["Tracing distribuído"]
        logs["Logs centralizados"]
    end

    users --> dns --> waf --> lb
    carriers --> waf
    lb --> api1 & api2 & api3
    api1 & api2 & api3 --> primary
    api1 & api2 & api3 --> replica
    api1 & api2 & api3 --> cache
    api1 & api2 & api3 --> broker
    broker --> workers --> push
    app -.-> obs
    async -.-> obs

    style borda fill:#fdf0f0,stroke:#c0392b,stroke-dasharray: 5 5
    style async fill:#fdf6e3,stroke:#b58900,stroke-dasharray: 5 5
    style obs fill:#f0f0fa,stroke:#6b4d8f,stroke-dasharray: 5 5
    style app fill:#eefaf3,stroke:#1f6f4a
    style dados fill:#dbe9f5,stroke:#2b5f8f
```

## Decomposição: o que sairia primeiro, e por quê

O monólito **não** se quebra em oito serviços porque tem oito módulos. A ordem abaixo segue carga e
independência de dado, não o organograma do pacote:

```mermaid
flowchart LR
    subgraph fica ["Núcleo transacional — FICA JUNTO"]
        m["missoes"]
        c["carteira"]
        g["geolocalizacao"]
        i["identidade"]
    end
    subgraph sai ["Extraível, nesta ordem"]
        n["1 · notificacoes<br/><i>fan-out é o que mais escala<br/>e não participa da transação</i>"]
        int["2 · integracoes<br/><i>já é adaptador puro,<br/>sem estado próprio</i>"]
        l["3 · logistica<br/><i>webhook tem perfil de carga<br/>próprio, ditado por terceiro</i>"]
    end
    fica -.->|"evento"| sai
    style fica fill:#eefaf3,stroke:#1f6f4a
    style sai fill:#fdf6e3,stroke:#b58900
```

**`missoes` + `carteira` não se separam sem custo alto.** Concluir uma missão credita a carteira,
paga o pote, concede XP e anuncia — hoje tudo numa transação. Separados, isso vira saga com
compensação, e a invariante de conservação (já a mais frágil do sistema, ver
[fluxo econômico](fluxo-economico.md)) passaria a depender de compensação correta sob falha
parcial. **Seria trocar uma garantia por um problema.**

**`notificacoes` sai primeiro** porque já está desacoplado por construção: a conclusão publica um
evento e não espera resposta. Trocar a tabela `outbox` por um broker é substituir o *transporte* —
o padrão outbox continua sendo a forma de tornar "mudar estado" e "anunciar" atômicos.

## O que cada camada resolveria, e o que ela custa

| Camada | Resolve | Custo |
|---|---|---|
| **TLS/WAF** | tráfego em claro, ataque de aplicação, DDoS volumétrico | ponto único; TLS termination exige que a app deixe de confiar em cabeçalho de cliente — já preparado via `RemoteIpValve` |
| **Balanceador + N instâncias** | disponibilidade e capacidade | força **sessão sem estado** (já é: JWT) e obriga o cache local Caffeine a virar distribuído, senão cada instância decide diferente |
| **Réplica de leitura** | radar geoespacial é leitura pesada | atraso de replicação: missão criada e ainda não visível no radar |
| **Broker** | fan-out e picos | operação a mais; entrega *at-least-once* continua exigindo idempotência no consumidor — que já existe |
| **Observabilidade** | hoje não se sabe o que está lento em produção | custo de armazenamento e disciplina de instrumentação |

## Por que nada disso está no repositório

**Escopo declarado: desenvolvimento 100% local.** Broker, Redis, proxy reverso, Prometheus e Grafana
foram deliberadamente cortados do MVP. Um item de infraestrutura que ninguém opera não é arquitetura
demonstrada — é `docker-compose` mais comprido.

O que **foi** feito para que essa evolução seja possível depois:

- **Sessão sem estado** (JWT RS256), então N instâncias não exigem sessão pegajosa;
- **Fronteira de módulo verificada por ArchUnit** e **seis referências sem FK** no schema, para que
  extrair um módulo seja recorte e não reescrita;
- **Outbox transacional**, que troca de transporte sem mudar a semântica;
- **Idempotência em toda operação de valor**, que é o pré-requisito de qualquer entrega
  *at-least-once*;
- **`RemoteIpValve` com `trusted-proxies` configurável**, ausente em dev — a aplicação já sabe que,
  atrás de proxy, o IP do cliente vem por um caminho confiável e nunca do cabeçalho cru
  ([ADR 0019](../adr/0019-borda-http-cabecalho-nao-confiavel.md)).

## Premissas do documento estratégico que este desenho não sustenta

O PETI da entrega acadêmica projeta **50.000 conexões WebSocket simultâneas**. Não há WebSocket
algum no sistema — a comunicação é REST com *polling* pelo TanStack Query, e a notificação é
persistida em `alerta` e lida por consulta. Sustentar aquele número exigiria, antes deste desenho,
uma decisão de transporte que nunca foi tomada. Ver
[`../DIVERGENCIAS-DOCUMENTACAO.md`](../DIVERGENCIAS-DOCUMENTACAO.md).
