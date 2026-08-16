# Execução do zero — o README seguido literalmente

**Data:** 2026-08-16 · **Fase:** F13 · **Máquina:** Fedora Linux, JDK 21 (SDKMAN), Node 22,
**podman** (não Docker Desktop).

Este documento existe porque um README de entrega que nunca foi executado é uma hipótese, não uma
instrução. O procedimento: **destruir volume e chaves**, seguir o README sem usar conhecimento
prévio do projeto, e corrigir o README a cada passo que falhasse.

## Ponto de partida destruído

```console
$ mv services/api/keys /tmp/keys-backup     # simula clone novo: keys/ é gitignored
$ make reset
 Container omnitribo-db Stopping
 Container omnitribo-db Removed
 Volume omni-tribo_pgdata Removing
 Volume omni-tribo_pgdata Removed
 Volume omni-tribo_pgdata Creating
 Container omnitribo-db Started
```

## Passo 1 — chaves

```console
$ bash tools/gerar-chaves-dev.sh
writing RSA key
Chaves RSA 2048-bit geradas:
  Privada: services/api/keys/private.pem
  Pública: services/api/keys/public.pem

$ ls -l services/api/keys/
-rw-------. 1 renan renan 1704 private.pem
-rw-r--r--. 1 renan renan  451 public.pem
```

A chave privada nasce `600`. Sem este passo nenhum contexto Spring sobe — é o erro nº 1 de quem
clona, e continua sendo o primeiro passo do README por isso.

## Passo 2 — banco

```console
$ docker compose ps --format '{{.Name}} | {{.Status}} | health={{.Health}}'
omnitribo-db | Up 50 seconds | health=healthy

$ docker compose exec -T db psql -U omnitribo -d omnitribo -tAc \
    "SELECT extname FROM pg_extension ORDER BY 1;"
pgcrypto
plpgsql
postgis
```

As extensões vêm de `docker/init/01-extensions.sql`, aplicado no primeiro boot do container. O banco
ainda **não tem schema** neste ponto — só as tabelas de sistema do PostGIS.

> **Correção feita no README a partir desta execução.** O README mandava "confirme que aparece
> *healthy*" após `make ps`. Nesta máquina `docker compose ps` imprime apenas `Up 50 seconds`, sem o
> sufixo `(healthy)` que o Docker Desktop mostra — o estado de saúde só aparece pedindo o campo
> explicitamente. Seguir a instrução ao pé da letra levaria a concluir, erradamente, que o banco não
> subiu. O README passou a dar o comando que funciona nos dois casos.

## Passo 3 — backend (é ele quem aplica schema e seed)

```console
$ cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

FlywayExecutor : Database: jdbc:postgresql://localhost:5432/omnitribo (PostgreSQL 16.9)
DbMigrate      : Successfully applied 25 migrations to schema "public", now at version v904
                 (execution time 00:00.686s)
TomcatWebServer: Tomcat started on port 8080 (http) with context path '/'
TomcatWebServer: Tomcat started on port 8090 (http) with context path '/'
ApiApplication : Started ApiApplication in 5.524 seconds
```

**25 migrations = 20 de schema (topo `V22`) + 5 de seed (`V900`–`V904`).** As duas portas sobem
juntas: 8080 para a API, 8090 para o actuator.

```console
$ curl -s http://localhost:8080/api/v1/ping
{"mensagem":"pong","horario":"2026-08-16T03:55:44.138825854Z"}
```

Dados de demonstração carregados pelo Flyway:

| usuários | tribos | missões | pontos de custódia | carteiras |
|---|---|---|---|---|
| 12 | 4 | 20 | 9 | 11 |

## Passo 4 — ciclo completo de missão, por HTTP

Dois ciclos ponta a ponta (`RASCUNHO → ABERTA → ACEITA → EM_ANDAMENTO → AGUARDANDO_CONFIRMACAO →
CONCLUIDA`), com dois usuários distintos em cada um, incluindo prévia de recompensa, financiamento
de pote e check-in geolocalizado aceito. Saída completa e leitura em
[`f13-conservacao-por-categoria.md`](f13-conservacao-por-categoria.md).

## Passo 5 — webhook de transportadora

```console
$ bash tools/carrier-mock/enviar.sh
  OK  caminho feliz → vira missão             HTTP 200
  OK  replay → mesma missão, sem duplicar     HTTP 200
  OK  assinatura inválida → 401               HTTP 401
  OK  timestamp de 10 min atrás → 401         HTTP 401
  OK  ponto lotado → 200 RECUSADA, sem missão HTTP 200
  OK  ponto inexistente → 404                 HTTP 404

Todos os 6 cenários responderam como esperado.
```

Os dois 401 são **indistinguíveis** no corpo (`"Assinatura do webhook inválida."`) embora as causas
sejam diferentes — assinatura errada e carimbo fora da janela. É deliberado
([ADR 0021](../adr/0021-verificacao-de-webhook-de-transportadora.md)): discriminar as causas diria a
um atacante qual metade ele já acertou.

### O que ficou gravado

```console
$ psql -c "SELECT ef.codigo_rastreio, ef.risco_faixa, ef.risco_probabilidade,
                  ef.risco_multiplicador, ef.risco_versao_modelo,
                  m.status, m.categoria, m.tokens_recompensa, m.nivel_minimo
             FROM entrega_falida ef LEFT JOIN missao m ON m.id = ef.missao_id
            WHERE ef.recusada_em IS NULL ORDER BY ef.recebido_em DESC LIMIT 1;"

  codigo_rastreio  | risco_faixa | risco_probabilidade | risco_multiplicador | versao | status | categoria | tokens | nivel_minimo
-------------------+-------------+---------------------+---------------------+--------+--------+-----------+--------+--------------
 BR1786852697FELIZ | MEDIO       |              0.1108 |                1.06 |      1 | ABERTA | ENTREGA   |     66 |            2
```

O score do modelo de risco foi **congelado na linha** junto com a versão do modelo, e o multiplicador
1,06 entrou na base do cálculo da recompensa — não sobre o total
([ADR 0022](../adr/0022-previsao-de-risco-de-entrega.md)).

### Fan-out e outbox

```console
$ psql -c "SELECT tipo, prioridade, count(*) FROM alerta GROUP BY 1,2 ORDER BY 1,2;"
           tipo            | prioridade | count
---------------------------+------------+-------
 ENTREGA_FALIDA_DISPONIVEL |          1 |     1
 FINANCIAMENTO_CONFIRMADO  |          0 |     1
 MISSAO_ACEITA             |          0 |     1
 MISSAO_CONCLUIDA          |          0 |     8
 MISSAO_PROXIMA            |          0 |     1
 PONTO_CUSTODIA_LOTADO     |          0 |     1

$ psql -c "SELECT tipo_evento,
                  count(*) FILTER (WHERE publicado_em IS NOT NULL) AS publicados,
                  count(*) FILTER (WHERE publicado_em IS NULL)     AS pendentes
             FROM outbox GROUP BY 1;"
       tipo_evento       | publicados | pendentes
-------------------------+------------+-----------
 EntregaFalidaConvertida |          1 |         0
 EntregaFalidaRecusada   |          1 |         0
 MissaoConcluida         |          4 |         0
```

**Prioridade 1** no alerta de entrega falida corresponde à faixa de risco `MEDIO` — a priorização do
fan-out lê a faixa que viajou no payload do evento, sem consultar o módulo `logistica`.

**Zero eventos pendentes na outbox**: o `DrenadorOutboxJob` drenou tudo. Vale notar que a recusa por
ponto lotado **também** gerou evento e alerta — recusar é um desfecho de negócio, não um erro.

## Resultado

O README foi seguido do zero com **uma correção necessária** (a verificação de saúde do container),
já aplicada. Os demais passos funcionaram como escritos.

## O que esta evidência NÃO garante

- Foi executado numa única máquina Linux com podman. Não prova o caminho em macOS, Windows ou Docker
  Desktop.
- Não cobre o app mobile em aparelho físico — só o backend e a API.
- Os tempos (boot em 5,5 s, migrations em 0,686 s) são desta máquina, com cache Maven quente.
