# Infraestrutura Local

## O que roda

| Serviço | Container       | Imagem                   | Porta |
|---------|-----------------|--------------------------|-------|
| Banco   | `omnitribo-db`  | `postgis/postgis:16-3.5` | 5432  |

Extensões habilitadas em `omnitribo`: `postgis` (consultas geoespaciais) e `pgcrypto` (UUIDs e hashes no banco).

## Pré-requisitos

- Docker Engine ≥ 24 com o plugin Compose v2 (`docker compose`, não `docker-compose`)
- Arquivo `.env` na raiz (copie `.env.example` na primeira vez)

## Primeiros passos

```bash
cp .env.example .env   # apenas na primeira vez
make up
```

## Credenciais de DEV

Definidas em `.env` (não versionado). Valores padrão de `.env.example`:

| Variável            | Valor padrão    |
|---------------------|-----------------|
| `POSTGRES_USER`     | `omnitribo`     |
| `POSTGRES_PASSWORD` | `omnitribo_dev` |
| `POSTGRES_DB`       | `omnitribo`     |

String de conexão: `postgresql://omnitribo:omnitribo_dev@localhost:5432/omnitribo`

## Comandos

| Comando      | O que faz                                                      |
|--------------|----------------------------------------------------------------|
| `make up`    | Sobe o banco em background                                     |
| `make down`  | Para e remove os containers (volume preservado)                |
| `make reset` | Destrói o volume e recria o banco do zero                      |
| `make logs`  | Tail nos logs do banco                                         |
| `make ps`    | Lista status dos containers                                    |
| `make psql`  | Abre cliente psql conectado ao banco local                     |

## Como resetar o banco

```bash
make reset
```

`reset` executa `docker compose down -v` (destrói o volume `pgdata`) e depois `docker compose up -d`.
O init script `docker/init/01-extensions.sql` reaplica as extensões. As migrations Flyway serão
reaplicadas na próxima inicialização do backend.

## Seed de Dev

O seed (`db/seed/V900__seed_dev.sql`) é carregado nos perfis `dev` e `test` automaticamente.
A faixa 900+ o mantém sempre depois de toda migration de schema — ver ADR 0006, Notas de manutenção.
**Nunca incluir em produção.** Senha de todos os usuários seed: `Senha@123`

| handle  | e-mail                   | papel   | tribo           |
|---------|--------------------------|---------|-----------------|
| `admin` | admin@omnitribo.dev      | ADMIN   | Tribo Pinheiros |
| `alice` | alice@omnitribo.dev      | USUARIO | Tribo Pinheiros |
| `bob`   | bob@omnitribo.dev        | USUARIO | Tribo Vila Madalena |
| `carol` | carol@omnitribo.dev      | USUARIO | Tribo Vila Madalena |
| `diana` | diana@omnitribo.dev      | USUARIO | Tribo Jardim América |
| `erik`  | erik@omnitribo.dev       | USUARIO | Tribo Jardim América |

### Cidade Líder — dados de demonstração (`V903__seed_cidade_lider.sql`)

Os usuários acima moram todos em Pinheiros / Vila Madalena. Quem abre o app na zona leste vê o radar
vazio, porque o radar é geoespacial de verdade — ele não inventa missão perto de quem procura. Este
seed povoa a região do **CEP 08280-460** (Rua Antônio Maria Bessa, Cidade Líder, São Paulo), em torno
de **-23.55737, -46.46987**, para a demonstração ser feita com o GPS ligado.

| handle    | e-mail                 | papel   | tribo              |
|-----------|------------------------|---------|--------------------|
| `renan`   | renan@omnitribo.dev    | USUARIO | Tribo Cidade Líder |
| `marlene` | marlene@omnitribo.dev  | USUARIO | Tribo Cidade Líder |
| `jonas`   | jonas@omnitribo.dev    | USUARIO | Tribo Cidade Líder |

O que entra junto: 3 pontos de custódia (LOCKER a 170 m, portaria a 342 m, loja a 4,1 km), 8 missões
nas quatro categorias entre 170 m e 4,5 km, 8 encomendas em `entrega_falida` — pendentes, convertidas
em missão aberta e convertidas em missão já concluída —, carteiras com ledger fechado e 3 alertas.

Duas coisas foram conferidas contra o sistema em execução, e não escritas de cabeça: **as recompensas
saíram de `POST /missoes/previa-recompensa`** (as 8 batem), e **os potes não cunham token** — os 156
em potes correspondem a 156 debitados de carteira como `FINANCIAMENTO_TRIBO`.

**Nota:** a V1 foi renomeada de `V1__extensions.sql` para `V1__extensoes.sql` nesta fase.
Se o banco local já tinha V1 aplicada, execute `make reset` antes de subir o backend.

## Verificação rápida

```bash
make psql
```
Dentro do psql:
```sql
SELECT postgis_version();   -- deve retornar 3.5.x
SELECT gen_random_uuid();   -- verifica pgcrypto
\d+ missao                  -- confirmar índices GiST em origem
\q
```
