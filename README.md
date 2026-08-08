# Omni-Tribo

App de **missões sociais hiperlocais gamificadas**. Usuários recebem missões no bairro — entregas
solidárias, coleta de recicláveis, mutirões, ajuda —, fazem check-in geolocalizado e recebem XP e
tokens comunitários, resgatáveis em benefícios de parceiros do bairro.

**A tese do produto:** uma entrega que falhou vira missão comunitária remunerada. Para o varejista,
entrega falha custa re-entrega, armazenagem e risco de perder o cliente; a missão de bairro é um
canal de última milha mais barato que a segunda tentativa.

Projeto acadêmico FIAP — Sistemas de Informação, RM 555833. Challenge Leroy Merlin: Sociedade 5.0 e
Logística.

---

## Estado

**Backend fechado até a F7**, auditado fase a fase. Build verde com **383 testes**, 0 falhas,
SpotBugs em `failOnError`. Mobile ainda não iniciado (F9+).

| | |
|---|---|
| Progresso por fase | [`docs/PROGRESSO.md`](docs/PROGRESSO.md) |
| Auditorias (F0–F7) | [`docs/auditoria/`](docs/auditoria/) |
| Decisões arquiteturais | [`docs/adr/`](docs/adr/) |

## Stack

Spring Boot 4.1 · Java 21 · PostgreSQL + PostGIS · Flyway · Caffeine · bucket4j
Expo SDK 57 · TypeScript strict · Expo Router · TanStack Query · Zustand *(F9+)*
JUnit 5 · Testcontainers · ArchUnit

Monólito modular, um pacote por módulo com `api/ dominio/ infra/`, fronteiras verificadas por
ArchUnit ([ADR 0001](docs/adr/0001-monolito-modular.md)).

## Como rodar

```bash
bash tools/gerar-chaves-dev.sh   # gera as chaves RSA de dev (services/api/keys/ é gitignored)
make up                          # sobe PostgreSQL + PostGIS (cria o .env sozinho)

cd services/api
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # API na 8080, actuator na 8081
./mvnw verify                                           # testes + spotless + spotbugs + jacoco
```

Swagger UI em `http://localhost:8080/swagger-ui.html`. Usuários de exemplo e credenciais de dev em
[`docs/INFRA.md`](docs/INFRA.md).

> Depois de qualquer migration nova, rode `make reset` num banco de dev já existente — o seed vive na
> faixa 900, então toda migration nasce *out-of-order*. Ver a seção Banco no `CLAUDE.md`.

## O que este projeto tenta demonstrar

Três garantias, cada uma com evidência executável em vez de afirmação:

- **Integridade transacional** — ledger append-only, idempotência sob lock, ordem determinística de
  lock entre carteiras, outbox transacional. Evidência de concorrência (100 threads, deadlock
  cruzado, rollback) em [`docs/qualidade/integridade-transacional.md`](docs/qualidade/integridade-transacional.md).
- **Geolocalização confiável** — validação 100% no servidor, com `EXPLAIN ANALYZE` provando uso do
  índice GiST sobre 200 mil linhas. O que os controles antifraude **não** pegam está escrito em
  [`docs/seguranca/antifraude-geolocalizacao.md`](docs/seguranca/antifraude-geolocalizacao.md).
- **Economia que fecha** — o token tem circuito fechado nas categorias comunitárias, e a conservação
  é medida, não suposta. As lacunas que permanecem estão declaradas com dono de fase, não escondidas.

## Documentação

| Onde | O quê |
|---|---|
| `CLAUDE.md` | memória do projeto: arquitetura, convenções, regras não negociáveis, pendências |
| `docs/adr/` | decisões com alternativas descartadas e o motivo real de cada recusa |
| `docs/auditoria/` | uma auditoria por fase, com evidência executada (SQL, `curl`, `EXPLAIN`) |
| `docs/seguranca/` | modelo de ameaça de autenticação e limites do antifraude |
| `docs/qualidade/` | evidência de build e de concorrência |
| `CONTRIBUTING.md` | Conventional Commits e checklist pré-commit |
