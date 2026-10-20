# Omni-Tribo

**Uma entrega que falhou vira missão comunitária remunerada.**

App de missões sociais hiperlocais. Quando o entregador não encontra ninguém, a encomenda fica num
ponto de custódia do bairro e nasce uma missão: um vizinho aceita, faz check-in geolocalizado, leva
ao destinatário e, quando a transportadora confirma o recebimento, recebe XP e tokens. Os tokens
viram benefícios no comércio do bairro (café, remendo de bicicleta, desconto na feira).

**A tese:** a segunda tentativa de entrega custa mais caro ao varejista que uma missão de bairro.
O custo do fracasso logístico vira renda de vizinho, e duas pessoas do mesmo bairro ganham um motivo
concreto para se encontrar.

Projeto acadêmico FIAP — Sistemas de Informação, RM 555833 · Enterprise Challenge Leroy Merlin —
SMART HAS & AI Logistics Extension.

## O que há dentro

- **Backend** Spring Boot 4.1 / Java 21: monólito modular com 8 módulos, fronteiras verificadas por
  ArchUnit, PostgreSQL + PostGIS e Flyway.
- **App** Expo SDK 57 / React Native, que roda no Expo Go: radar e mapa de missões, check-in por
  GPS, carteira, benefícios e caixa de avisos.
- **AI Logistics Extension:** um modelo de risco de falha de entrega (regressão logística em Java
  puro, treinada no build) que decide quanto a missão paga e o que o vizinho deve fazer para dar
  certo desta vez. Ver [docs/MODELO-RISCO.md](docs/MODELO-RISCO.md).
- **Economia com invariante medida:** ledger append-only, idempotência sob lock e emissão de token
  num ponto único e auditado. Ver [docs/ECONOMIA.md](docs/ECONOMIA.md).

| Documento | Conteúdo |
|---|---|
| [docs/ARQUITETURA.md](docs/ARQUITETURA.md) | módulos, máquina de estados, fluxo da entrega falida, segurança |
| [docs/ECONOMIA.md](docs/ECONOMIA.md) | moedas, recompensa, de onde sai o token, a invariante |
| [docs/MODELO-RISCO.md](docs/MODELO-RISCO.md) | o modelo, as 14 features, as métricas |
| [docs/DECISOES.md](docs/DECISOES.md) | as decisões de arquitetura, com a alternativa descartada |
| [apps/mobile/README.md](apps/mobile/README.md) | endereço da API no celular, firewall, build EAS |

## Rodar

Pré-requisitos: JDK 21, Node 22, Docker (ou podman) e o Expo Go no celular.

```bash
make demo                                                                  # chaves RSA + banco do zero
cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # terminal 1 — API :8080
cd apps/mobile && npm install && npm start                                 # terminal 2 — leia o QR no Expo Go
```

- O celular precisa alcançar o PC pela rede: `localhost` no celular é o próprio celular. Ver
  [apps/mobile/README.md](apps/mobile/README.md).
- Swagger em `http://localhost:8080/swagger-ui.html`. O actuator fica na **8090** (a 8081 é do
  Metro).
- Usuários seed, todos com senha `Senha@123`:
  - `admin@omnitribo.dev` (ADMIN);
  - `renan@omnitribo.dev` (executor da demo);
  - `alice`, `bob`, `carol`, `diana` e `erik` `@omnitribo.dev`.
- Simular a transportadora (entrega falida e confirmação): `bash tools/carrier-mock/enviar.sh`.

**Testes:** `make test` roda `./mvnw verify` (testes, Spotless, SpotBugs e gates JaCoCo) e
`npm test`. Também existem `cd apps/mobile && npm run typecheck && npm run lint`, e o e2e contra a
API de pé: `E2E_API_URL=http://<ip>:8080 npm run test:e2e`.

### Problemas comuns

| Sintoma | Causa e saída |
|---|---|
| `Validate failed: Detected resolved migration not applied` | migration nova num banco antigo → `make reset` |
| `duplicate key` logo depois de renomear uma migration | `cd services/api && ./mvnw clean` e `make reset` |
| Login não responde, `/api/v1/ping` responde | o banco foi recriado com a API de pé → reinicie a API |
| `BindException` no boot | porta ocupada (`ss -lptn 'sport = :8080'`) |
| Celular não conecta | firewall: `sudo firewall-cmd --add-port=8080/tcp --add-port=8081/tcp`, e mesma rede |
| `./mvnw verify` falha por PEM | rode `bash tools/gerar-chaves-dev.sh` |

## Contribuir

- Uma branch por frente (`feat/…`, `fix/…`, `chore/…`), com PR para `develop`. Nunca commitar em
  `main`.
- Conventional Commits em português: `tipo(escopo): descrição`.
- Antes de commitar: verificação verde e nenhum segredo no diff (o hook `checar-segredo.sh` e o
  gitleaks do CI são a segunda barreira).
- Migration nova: use a skill `/migration`. O hook `guardar-migration.sh` recusa número inválido.
