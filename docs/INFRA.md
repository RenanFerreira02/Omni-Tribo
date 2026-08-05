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

O V9 (`db/seed/V9__seed_dev.sql`) é carregado nos perfis `dev` e `test` automaticamente.
**Nunca incluir em produção.** Senha de todos os usuários seed: `Senha@123`

| handle  | e-mail                   | papel   | tribo           |
|---------|--------------------------|---------|-----------------|
| `admin` | admin@omnitribo.dev      | ADMIN   | Tribo Pinheiros |
| `alice` | alice@omnitribo.dev      | USUARIO | Tribo Pinheiros |
| `bob`   | bob@omnitribo.dev        | USUARIO | Tribo Vila Madalena |
| `carol` | carol@omnitribo.dev      | USUARIO | Tribo Vila Madalena |
| `diana` | diana@omnitribo.dev      | USUARIO | Tribo Jardim América |
| `erik`  | erik@omnitribo.dev       | USUARIO | Tribo Jardim América |

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
