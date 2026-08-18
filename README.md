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

## O problema

Duas dores que se resolvem uma à outra.

**Do lado social:** solidão urbana, vínculos de vizinhança enfraquecidos, e aplicativos que ampliam
a conectividade virtual enquanto o isolamento cresce. Falta um motivo concreto para duas pessoas do
mesmo bairro se encontrarem.

**Do lado logístico:** a entrega falida. Ninguém em casa, o pacote volta para o centro de
distribuição, e o varejista paga re-entrega, armazenagem e risco de perder o cliente — um custo que
hoje não vira valor para ninguém.

**A tese:** a segunda tentativa de entrega é mais cara que uma missão de bairro. Se o pacote puder
ficar num ponto de custódia e um vizinho for remunerado para retirá-lo, o custo do fracasso vira
renda comunitária. É o mesmo evento resolvendo os dois problemas.

## Estado

**Backend e app mobile implementados**, com verificação executada em 2026-08-16:

| | Testes | Falhas | Evidência |
|---|---|---|---|
| Backend — JUnit 5, Testcontainers, ArchUnit | **637** | 0 (2 pulados) | [`f13-make-test.md`](docs/evidencias/f13-make-test.md) |
| Mobile — Jest, RTL, MSW (14 suítes) | **179** | 0 | idem |

`./mvnw verify` não é só teste: inclui Spotless, SpotBugs em `failOnError` e **dois gates de
cobertura** JaCoCo que barram o build (80% global, 85% nos pacotes `dominio`).

| | |
|---|---|
| Progresso por fase e notas de manutenção | [`docs/PROGRESSO.md`](docs/PROGRESSO.md) |
| Como o projeto se auto-corrigiu | [`docs/EVOLUCAO-ARQUITETURAL.md`](docs/EVOLUCAO-ARQUITETURAL.md) |
| Auditorias com evidência executada | [`docs/auditoria/`](docs/auditoria/) |
| Decisões arquiteturais | [`docs/adr/`](docs/adr/) |
| Diagramas | [`docs/diagramas/`](docs/diagramas/) |

### O que falta, dito por extenso

**ENTREGA e AJUDA cunham token sem lastro.** O pote comunitário cobre TRIBO e COLETA; nas outras
duas categorias o token nasce do nada na conclusão, porque a carteira de patrocinador — o
financiador correto dessas categorias — não foi implementada. Medido do zero:
`SUM(saldos) + SUM(potes)` cresceu exatamente o valor da recompensa num ciclo AJUDA e ficou parado
num ciclo TRIBO ([evidência](docs/evidencias/f13-conservacao-por-categoria.md)).

Não é descuido: exigir pote para ENTREGA faria membros da tribo custearem a logística do varejista,
que é o inverso do modelo. Preferiu-se uma lacuna documentada a uma regra errada codificada — a
íntegra está em [`docs/EVOLUCAO-ARQUITETURAL.md`](docs/EVOLUCAO-ARQUITETURAL.md).

Também pendentes: os testes de carga da F12b e as demais pendências da seção final do
[`CLAUDE.md`](CLAUDE.md).

## Arquitetura

**Monólito modular** — oito módulos, um pacote cada, com `api/` (controllers, DTOs, portas),
`dominio/` (entidades, regras) e `infra/` (repositórios, clientes):

```
compartilhado · identidade · missoes · geolocalizacao · carteira · logistica · notificacoes · integracoes
```

**Módulo só acessa outro por `api/` pública ou por evento** — nunca repositório ou entidade JPA
alheia. A regra é verificada por ArchUnit (`RegrasArquiteturaTest`), não por disciplina, e é o que
tornaria a extração de um módulo em serviço um trabalho de recorte em vez de reescrita. É também a
razão de seis referências entre módulos serem UUID puro **sem** foreign key no banco.

Diagramas em [`docs/diagramas/`](docs/diagramas/): [contexto e contêineres](docs/diagramas/c4-contexto-e-conteineres.md) ·
[máquina de estados](docs/diagramas/maquina-estados.md) · [ciclo da missão](docs/diagramas/sequencia-ciclo-missao.md) ·
[entrega falida](docs/diagramas/sequencia-entrega-falida.md) · [fluxo econômico](docs/diagramas/fluxo-economico.md) ·
[ER do banco](docs/diagramas/er-banco.md) · [arquitetura-alvo em escala](docs/diagramas/arquitetura-alvo.md).

## Stack, e por que cada peça

| Peça | Por quê |
|---|---|
| **Monólito modular** (Spring Boot 4.1 · Java 21) | Um time, um deploy, uma transação. Microsserviço aqui pagaria o custo de coordenação distribuída sem ter o problema que ele resolve — e as fronteiras ficam prontas para extrair depois ([ADR 0001](docs/adr/0001-monolito-modular.md)) |
| **PostgreSQL + PostGIS** | O produto é geoespacial: raio de check-in, radar de proximidade, tribos por distância. PostGIS resolve isso **no banco**, com índice GiST, em vez de trazer linhas para filtrar em Java ([ADR 0002](docs/adr/0002-postgresql-postgis.md)) |
| **Flyway** | Schema versionado é a única fonte de verdade; `ddl-auto` é sempre `validate`. Divergência vira migration, nunca ajuste silencioso |
| **JWT RS256 + Argon2** | Assimétrico para que verificar não exija o segredo de assinar; Argon2 por ser resistente a GPU, ao contrário de hash rápido ([ADR 0005](docs/adr/0005-autenticacao-jwt-argon2.md)) |
| **Outbox transacional** | Anunciar a conclusão e creditar a carteira precisam ser atômicos. Sem broker no MVP, a outbox dá essa atomicidade com uma tabela ([ADR 0008](docs/adr/0008-ledger-append-only-e-idempotencia.md)) |
| **Expo / React Native** | Uma base de código, iOS e Android, e demonstração sem build nativo — o app roda no Expo Go pelo QR ([ADR 0003](docs/adr/0003-react-native-expo.md)). O que essa escolha **custou** está em [`docs/COMPARATIVO-TECNOLOGIAS.md`](docs/COMPARATIVO-TECNOLOGIAS.md) |
| **Testcontainers** | Teste geoespacial em H2 não prova nada: PostGIS real, subido pelo próprio teste |
| **ArchUnit** | Fronteira de módulo que não é testada vira comentário desatualizado |
| **Caffeine · bucket4j** | Cache com expiração (o anterior, sem despejo, era vetor de exaustão de memória) e limitação de tentativas de login |

---

# Do clone à execução em 5 comandos

```bash
bash tools/gerar-chaves-dev.sh                                    # 1. chaves RSA (obrigatório)
make up                                                            # 2. banco (cria o .env sozinho)
cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # 3. API :8080
cd apps/mobile && npm install                                      # 4. dependências do app
npm start                                                          # 5. Metro — leia o QR no Expo Go
```

Os passos 3 e 5 ficam em terminais separados. **O `.env` não é passo manual** — o Makefile o cria a
partir do `.env.example`. Este caminho foi executado do zero, com volume e chaves destruídos antes,
e a saída real está em [`docs/evidencias/f13-execucao-do-zero.md`](docs/evidencias/f13-execucao-do-zero.md).

Para rodar os testes: **`make test`** (backend + mobile). Detalhe na seção [Verificar](#6-verificar).

O passo a passo comentado, com o que fazer quando algo falha, continua abaixo.

---

# Os mesmos 5 passos, comentados

## 1. Pré-requisitos

| O quê | Versão | Por quê |
|---|---|---|
| **JDK 21** | 21 | O `pom.xml` fixa `java.version=21`, e o CI usa Temurin 21 |
| **Node** | 22 | Versão do CI do mobile |
| **Docker** | qualquer recente | Sobe o PostgreSQL + PostGIS |
| Android SDK + AVD | opcional | Só para `npm run android`. **Não é preciso para testar no celular** |

> **Atenção ao JDK em distribuições Linux:** o `java` do PATH costuma ser JRE-only, e o Maven precisa
> de um JDK. Se `./mvnw` reclamar, aponte o `JAVA_HOME`:
> ```bash
> export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which javac))))
> # ou, com SDKMAN: export JAVA_HOME=~/.sdkman/candidates/java/current
> ```

## 2. Clone e chaves

```bash
git clone https://github.com/RenanFerreira02/Omni-Tribo.git
cd Omni-Tribo

bash tools/gerar-chaves-dev.sh
```

**O passo das chaves não é opcional.** `services/api/keys/` é gitignored, e sem os PEM o
`@PostConstruct` do `JwtService` lança e **nenhum contexto Spring sobe** — nem para rodar testes. É
o erro nº 1 de quem clona.

O `.env` **não** é passo manual: o Makefile o cria a partir do `.env.example` sozinho.

## 3. Banco

```bash
make up      # sobe o PostgreSQL + PostGIS e cria o .env
make ps      # o container deve aparecer como "Up"
```

O Flyway aplica o schema e, nos perfis `dev`/`test`, também o seed — com tribos, usuários, missões e
pontos de custódia prontos para uso.

| Comando | O que faz |
|---|---|
| `make up` | sobe o container (e cria o `.env` se faltar) |
| `make down` | para o container — **o volume é preservado, isto não apaga dado nenhum** |
| `make reset` | **destrói o volume** e recria o banco vazio |
| `make ps` · `make logs` · `make psql` | status · tail nos logs · abre um psql no banco |

### Voltar o banco ao estado original

Depois de aceitar missões, transferir tokens ou fazer check-in testando, o caminho para recomeçar do
zero é o `reset` — e ele tem **três passos**, não um:

```bash
# 1. pare o backend (Ctrl+C). Derrubar o banco com o pool aberto só gera erro de conexão.

# 2. na RAIZ do projeto — destrói o volume e recria o container
make reset

# 3. suba o backend: é aqui que os dados voltam
cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

**O passo 3 é o que costuma ser esquecido.** `make reset` apenas recria o container com as extensões
do `docker/init/`; quem aplica o schema (`V1`–`V22`) e depois o seed (`V900`–`V904`) é o Flyway, no
boot da aplicação. Sem subir o backend, o banco fica vazio. O seed só entra porque
`application-dev.yml` inclui `classpath:db/seed` nas locations — o perfil de produção não inclui,
então dado de demonstração não tem como vazar para lá.

Cuidado com o vizinho: **`make down` não reseta nada**, preserva o volume de propósito. Quem apaga é
o `-v` do `reset`.

No app, faça **logout e login de novo** logo depois. Os IDs do seed são fixos e as chaves em
`services/api/keys/` não são tocadas, então seu access token continua válido — mas a tabela
`refresh_token` foi junto com o volume, e em até 15 minutos a rotação falha e o app desloga sozinho
no meio do uso.

> **Desfazer só as missões aceitas, com `UPDATE`, não é uma alternativa.** Aceitar grava linha em
> `missao_evento`, e aceite e conclusão movimentam `lancamento` — as duas tabelas são append-only,
> corrigidas por estorno e nunca por `UPDATE`. Editar o estado à mão produz um histórico que não
> explica o saldo atual, que é exatamente o que a reconciliação existe para detectar. O reset
> completo custa poucos segundos porque o seed reconstrói tudo.

**Migration nova também exige `make reset`** num banco de dev já existente. Como a `V900` do seed já
está aplicada, qualquer `V23` nova tem versão *menor* que o topo do histórico, o Flyway a classifica
como *out-of-order* — desligado no dev de propósito — e o boot morre com `Validate failed: Detected
resolved migration not applied to database`, sem mencionar seed nem ordenação em lugar nenhum.

## 4. Backend

```bash
cd services/api
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

API em `http://localhost:8080`, actuator em **8090** (porta diferente, de propósito — e não 8081,
que é do Metro/Expo, ver `application.yml`). Swagger UI em
`http://localhost:8080/swagger-ui.html`.

**No VS Code, use a configuração de execução `ApiApplication (dev)`** (painel *Run and Debug*), não
o botão *Run* que aparece em cima do `main` de `ApiApplication.java`. Aquele botão sobe o processo
no perfil default e com o diretório de trabalho na raiz do repositório, e as duas coisas quebram o
boot: `application.yml` declara `DATASOURCE_URL` sem default de propósito, e as chaves RSA são lidas
por caminho relativo a `services/api/`. O `launch.json` versionado já traz as duas correções.

Confira com:

```bash
curl http://localhost:8080/api/v1/ping
# {"mensagem":"pong","horario":"..."}
```

## 5. App mobile

Em **outro terminal**, com o backend rodando:

```bash
cd apps/mobile
npm install
npm start
```

Leia o QR com o **[Expo Go](https://expo.dev/go)**. **Não é preciso development build** — todos os
módulos nativos do app estão no Expo Go do SDK 57, incluindo `react-native-webview` e o seletor de
data.

### O endereço da API — o erro que mais trava iniciante

`localhost` dentro do celular é o **próprio celular**, não a sua máquina.

| Onde o app roda | baseURL |
|---|---|
| **Celular físico** (Expo Go, mesma Wi-Fi) | `http://<IP-da-sua-máquina>:8080` |
| **Emulador Android** | `http://10.0.2.2:8080` (alias do host visto de dentro do emulador) |
| **Expo Web** | `http://localhost:8080` |

Na maioria dos casos **você não precisa configurar nada**: o app deriva o endereço do host do Metro,
que já é o da sua máquina. Se precisar fixar:

```bash
ip -4 addr show scope global | grep -oP 'inet \K[\d.]+'   # descubra o IP
EXPO_PUBLIC_API_URL=http://192.168.15.6:8080 npm start     # use o seu
```

**No Linux, libere a porta no firewall** — senão o celular não alcança o backend e o sintoma é um
genérico "sem conexão":

```bash
sudo firewall-cmd --add-port=8080/tcp              # só nesta sessão
sudo firewall-cmd --add-port=8080/tcp --permanent && sudo firewall-cmd --reload
```

O Spring já escuta em todas as interfaces (default do Boot); não há nada a configurar nele.

### Entrar no app

Usuários do seed, todos com a senha `Senha@123`:

| E-mail | Papel | Tribo |
|---|---|---|
| `alice@omnitribo.dev` | Usuário | Pinheiros |
| `bob@omnitribo.dev` | Usuário | Vila Madalena |
| `carol@omnitribo.dev` | Usuário | Vila Madalena |
| `admin@omnitribo.dev` | Admin | Pinheiros |

`bob` e `carol` estão na **mesma tribo** — é o par para testar transferência de tokens, que só
acontece entre membros da mesma tribo. Lista completa em [`docs/INFRA.md`](docs/INFRA.md).

> **Login tem limite de 5 tentativas por minuto.** Se aparecer "Muitas tentativas", espere um
> minuto: é o bloqueio antifraude funcionando, não um defeito.

## 6. Verificar

```bash
make test                               # backend (mvnw verify) + mobile (npm test)
```

Ou cada lado separadamente:

```bash
cd services/api && ./mvnw verify        # testes + spotless + spotbugs + 2 gates jacoco
cd apps/mobile  && npm run typecheck && npm run lint && npm test

# integração REAL contra a API em execução (fora do npm test de propósito)
cd apps/mobile && E2E_API_URL=http://localhost:8080 npm run test:e2e
```

**O `verify` barra por mais coisa que teste vermelho:** SpotBugs roda com `failOnError`, e o JaCoCo
tem dois gates — 80% de instruções global e 85% nos pacotes `dominio`. Achado de análise estática ou
queda de cobertura quebram o build como um teste quebraria.

A saída real da última execução está em
[`docs/evidencias/f13-make-test.md`](docs/evidencias/f13-make-test.md).

> **Se `./mvnw verify` falhar ao subir o banco de teste:** a suíte usa Testcontainers, que precisa de
> um socket de container acessível. Com Docker Desktop funciona sem configuração; com **podman**,
> exporte o socket antes:
> ```bash
> export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
> ```

## Se algo der errado

| Sintoma | Causa provável |
|---|---|
| Contexto Spring não sobe, erro no `JwtService` | Faltou `bash tools/gerar-chaves-dev.sh` |
| `Validate failed: Detected resolved migration not applied` | Migration nova num banco antigo — rode `make reset` |
| Dados sujos de teste; quero o seed de volta | `make reset` **e suba o backend** — ver [Voltar o banco ao estado original](#voltar-o-banco-ao-estado-original) |
| Banco vazio depois do `make reset` | Faltou o passo 3: o Flyway só aplica schema e seed no boot da aplicação |
| App desloga sozinho pouco depois de um reset | Esperado — a `refresh_token` foi com o volume. Faça logout/login |
| `duplicate key value violates unique constraint` após renomear migration | Rode `./mvnw clean` — o Maven manteve o arquivo antigo em `target/classes` |
| App no celular não conecta | Firewall na 8080, ou celular em outra rede |
| `Muitas tentativas. Aguarde 60 segundos` | Bloqueio de login, 5/min. Espere |
| Mapa cinza ou sem tiles | O mapa exige internet (tiles do OpenStreetMap) |

Mais detalhes de rede e emulador em [`apps/mobile/README.md`](apps/mobile/README.md); containers e
credenciais em [`docs/INFRA.md`](docs/INFRA.md).

---

## O que este projeto tenta demonstrar

Quatro garantias, cada uma com evidência executável em vez de afirmação:

- **Integridade transacional** — ledger append-only, idempotência sob lock, ordem determinística de
  lock entre carteiras, outbox transacional. Evidência de concorrência (100 threads, deadlock
  cruzado, rollback) em [`docs/qualidade/integridade-transacional.md`](docs/qualidade/integridade-transacional.md).
- **Geolocalização confiável** — validação 100% no servidor, com `EXPLAIN ANALYZE` provando uso do
  índice GiST sobre 200 mil linhas. O que os controles antifraude **não** pegam está escrito em
  [`docs/seguranca/antifraude-geolocalizacao.md`](docs/seguranca/antifraude-geolocalizacao.md).
- **Economia que fecha — e o que nela ainda não fecha** — quem cria a missão não paga; a recompensa é
  calculada pelo servidor e congelada na criação, e o app nunca reimplementa a fórmula
  ([ADR 0009](docs/adr/0009-economia-do-cuidado-token-como-recompensa.md)). Uma auditoria provou isso
  com caso hostil: `xpRecompensa: 99999` no corpo virou `60` no banco. O que **não** fecha — a
  cunhagem em ENTREGA e AJUDA — está medido e explicado, não omitido
  ([evidência](docs/evidencias/f13-conservacao-por-categoria.md)).
- **Auditoria com medição, não leitura** — cada fase confrontada com a especificação executando SQL,
  `curl` e os próprios testes. Cinco dos sete defeitos da rodada F0→F7 eram invisíveis lendo o
  código, e as auditorias do mobile acharam dois defeitos que uma revisão comum deixou passar.

## Documentação

**Comece por aqui**, nesta ordem:

| Ordem | Documento | Por quê |
|---|---|---|
| 1 | [`docs/EVOLUCAO-ARQUITETURAL.md`](docs/EVOLUCAO-ARQUITETURAL.md) | a linha do tempo das decisões e o defeito econômico que a auditoria achou — como foi detectado e por que a reconciliação não o pegou |
| 2 | [`docs/diagramas/`](docs/diagramas/) | o sistema em sete diagramas, incluindo a arquitetura-alvo em escala |
| 3 | [`docs/ROTEIRO-DEMO.md`](docs/ROTEIRO-DEMO.md) | demonstração de 10 min com comandos exatos e plano B |
| 4 | [`docs/DIVERGENCIAS-DOCUMENTACAO.md`](docs/DIVERGENCIAS-DOCUMENTACAO.md) | onde a implementação diverge do documento estratégico, e por quê |

Referência completa:

| Onde | O quê |
|---|---|
| `CLAUDE.md` | memória do projeto: arquitetura, convenções, regras não negociáveis, pendências |
| `services/api/CLAUDE.md` · `apps/mobile/CLAUDE.md` | convenções e armadilhas de cada camada |
| [`docs/PROGRESSO.md`](docs/PROGRESSO.md) | tabela de fases e **notas de manutenção** — o log de por que cada correção estrutural foi feita |
| [`docs/adr/`](docs/adr/) | 23 decisões com alternativas descartadas e o motivo real de cada recusa |
| [`docs/auditoria/`](docs/auditoria/) | 10 auditorias, com evidência executada (SQL, `curl`, `EXPLAIN`) |
| [`docs/evidencias/`](docs/evidencias/) | saídas reais de medição — [índice](docs/evidencias/README.md) |
| [`docs/qualidade/`](docs/qualidade/) | evidência de build, concorrência, modelo de risco e a [matriz de rastreabilidade](docs/qualidade/matriz-rastreabilidade.md) requisito→teste→evidência |
| [`docs/seguranca/`](docs/seguranca/) | modelo de ameaça de autenticação e limites do antifraude |
| [`docs/COMPARATIVO-TECNOLOGIAS.md`](docs/COMPARATIVO-TECNOLOGIAS.md) | Flutter × Kotlin nativo × React Native, com o custo real de cada escolha |
| [`docs/INFRA.md`](docs/INFRA.md) | containers, credenciais de dev, lista completa de usuários seed |
| [`CHANGELOG.md`](CHANGELOG.md) | uma entrada por fase, F0 a F13 |
| `CONTRIBUTING.md` | Conventional Commits e checklist pré-commit |
| `tools/carrier-mock/` · `tools/dataset/` · `tools/evidencias/` | webhook de transportadora · dataset e treino do modelo de risco · scripts de medição |
| `documentacao/` | PDF da entrega acadêmica. **Não é fonte de verdade técnica** — ver as divergências |
