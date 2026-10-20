# CLAUDE.md

Omni-Tribo: app de missões sociais hiperlocais. **Uma entrega que falhou vira missão comunitária
remunerada.** A transportadora reporta a falha, a encomenda fica num ponto de custódia, um vizinho
aceita, faz check-in por GPS, e é creditado em XP + TOKEN quando a transportadora confirma.
Projeto acadêmico FIAP (RM 555833), exposto no FIAP NEXT.

Leia antes de mexer: [docs/ARQUITETURA.md](docs/ARQUITETURA.md) · [docs/ECONOMIA.md](docs/ECONOMIA.md)
· [docs/MODELO-RISCO.md](docs/MODELO-RISCO.md) · [docs/DECISOES.md](docs/DECISOES.md) (os números
"ADR 00NN" citados no código apontam para lá).
Convenções por camada: `services/api/CLAUDE.md` e `apps/mobile/CLAUDE.md`, que carregam sozinhos.

## Escopo

100% local: um Postgres+PostGIS em Docker, a API Spring Boot e o app Expo no Expo Go. **Não
adicione** broker, Redis, proxy reverso, Prometheus/Grafana, push remoto (FCM/APNs), pagamento real,
KYC, cotação token→real, dark mode, fonte custom, i18n, Detox ou Maestro sem perguntar: foram
avaliados e recusados. As colunas `dispositivo.push_token` e o BRL/`SaqueService` ficam inertes de
propósito, não são pendência.

## Arquitetura em uma tela

- Monólito modular, raiz `com.omnitribo`, módulos `compartilhado · identidade · missoes ·
  geolocalizacao · carteira · logistica · notificacoes · integracoes`, cada um com
  `api/ dominio/ infra/`.
- **Módulo só fala com outro por porta em `api/` ou por evento** (ArchUnit). Nunca repositório ou
  entidade JPA alheia. Injete a porta pela **interface**. `compartilhado/infra` é privado.
- A regra é direcional: `compartilhado` não importa tipo de módulo, por isso status e categoria
  entram como `String` em `ConsultasGeoespaciais`.
- Todo `ST_*` vive só em `ConsultasGeoespaciais`.
- O schema está à frente do código: tabela sem código correspondente é esperado.

## Economia (detalhe em docs/ECONOMIA.md)

- **Quem cria a missão não paga** (422 se financiar a própria). A recompensa XP+TOKEN é calculada
  pelo servidor e **congelada**, e o DTO de criação não tem valor de recompensa.
- O **multiplicador de risco** entra na BASE (junto da complexidade), em [1,00; 1,50]. Mudou o YAML
  da recompensa? Suba `versao`.
- `missao.fonte_pote` decide quem paga: `COMUNIDADE`, `PATROCINADOR` ou `SEM_TOKEN` (`CUNHAGEM` é legado, ADR 0038).
  Publicar exige pote cobrindo a recompensa. Cancelar ou expirar **estorna**, e isso acontece em
  `MissaoService.aplicar` **e** em `ExpiracaoMissoesService.expirarUma`.
- `CONCLUIDA` é o único estado que credita. **Nenhuma missão tem `valor_brl > 0`.**
- A invariante tem duas pontas: `APORTE_PATROCINADOR` emite, o resgate queima. Reconciliação (`integro`) não
  detecta emissão.

## Comandos

```bash
bash tools/gerar-chaves-dev.sh          # clone novo: sem PEM nenhum contexto Spring sobe
make demo                               # chaves + banco do zero (recusa se a 8080 estiver ocupada)
make feira                              # idem + o roteiro do estande (API em dev,feira; docs/FEIRA.md)
cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # API 8080, actuator 8090
cd services/api && ./mvnw verify        # testes + Spotless + SpotBugs + gates JaCoCo
cd services/api && ./mvnw spotless:apply
cd services/api && ./mvnw -Dtest=NomeTest test
cd apps/mobile && npm start             # Metro (8081) → QR no Expo Go
cd apps/mobile && npm run typecheck && npm run lint && npm test
cd apps/mobile && E2E_API_URL=http://<ip>:8080 npm run test:e2e   # contra a API de pé, fora do CI
make up | down | reset | logs | ps | psql
```

- Compose sempre por `tools/demo/compose.sh` (o Makefile já usa).
- O actuator fica na **8090**, porque a 8081 é do Metro: não volte para lá.
- Pacote Expo se instala com `npx expo install`.
- `verify` reprova por SpotBugs e por JaCoCo (80% global, 85% em `dominio`, sobre INSTRUCTION). Não
  troque para BRANCH.

## Regras não negociáveis

**Versões:** nunca de memória. Verifique no Maven Central, npm ou start.spring.io; se não conseguir,
pergunte.

**Banco**
- Flyway é a única fonte de schema, e `ddl-auto` é sempre `validate`.
- A versão de migration é uma **sequência global**: schema em `db/migration` (V1–V8, V11–V31;
  **próxima V32**; **V9 e V10 queimadas**) e seed só dev/test em `db/seed` (V900+; **próximo
  V909**). Use `/migration`.
- Migration nova em banco de dev existente exige `make reset`. Renomeou migration? `./mvnw clean`
  também. **Nunca edite migration já aplicada.**
- Dinheiro é `numeric(12,2)`/BigDecimal; tokens são `bigint`; coordenada é
  `geography(POINT,4326)`; tempo é `timestamptz`; enum é varchar + CHECK + `EnumType.STRING`.
- `lancamento`, `auditoria` e `checkin` são append-only: correção é estorno.

**Segurança**
- Segredo nunca em arquivo versionado (`${VARIAVEL}` + `.env.example`).
- Identidade do usuário vem SEMPRE do JWT. Controller só trabalha com DTO/record.
- SQL sempre bindada. Erro é RFC 9457 com `type` do catálogo `TipoProblema`.
- Nunca logue senha, token, coordenada exata ou payload autenticado.
- Validação geoespacial e de saldo é sempre no servidor.
- O HMAC do webhook é sobre o corpo bruto, comparado em tempo constante.
- Mobile: credencial só via `src/lib/armazenamentoSeguro.ts`, nunca AsyncStorage.
- Deep link é entrada não confiável.

**Concorrência**
- Toda operação de valor segue locks → sondar a idempotência → validar → escrever.
- Ordem dos locks: `missao` → `carteira` (id crescente) → `usuario`.
- **`REQUIRES_NEW` é proibido no caminho de valor** (deadlock de pool).

**Testes**
- Endpoint novo nasce com teste de caminho feliz e de erro.
- Integração usa Testcontainers com PostGIS real.
- Operação de valor exige teste de concorrência multi-thread.
- Sem teste sem assertion. Se um teste acusar bug, corrija o CÓDIGO; não relaxe a assertion nem use
  `@Disabled`.

**Git**
- Conventional Commits, uma branch por frente, PR para `develop`. Nunca commite em `main`.
- Nunca `push --force` nem `reset --hard` sem pedido explícito.
- Merge de branches que tocam o mesmo serviço exige `./mvnw verify` depois do merge.

## Como trabalhar comigo

- Tarefa não trivial: planeje, mostre e espere aprovação.
- Não diga "pronto" sem ter executado a verificação e colado a saída real.
- Pedido ambíguo, ou que contradiz este arquivo: diga antes de codar.
- Comente o PORQUÊ (segurança, concorrência, economia), não a história. Preciso defender o código
  oralmente.
- Português no domínio (`Missao`, `Carteira`) e nas mensagens; inglês nos termos técnicos
  (`Repository`, `Service`, `Dto`).
- Decisão arquitetural relevante entra em `docs/DECISOES.md`.

## Hooks (`.claude/hooks/`)

- `checar-segredo.sh` **nega** `git commit` com chave PEM ou `senha = "..."` no diff.
- `guardar-migration.sh` **nega** migration com nome ou número inválido: queimado, repetido em
  qualquer ref, seed abaixo de 900 ou schema acima de 900.
- `formatar-java.sh` roda Spotless ao fim do turno.
- `formatar-mobile.sh` roda Prettier no arquivo editado.

## Usuários seed

Senha `Senha@123`:
- `admin@omnitribo.dev` (ADMIN);
- `alice` e `carol` (Pinheiros e Vila Madalena);
- `bob`, `diana` e `erik`;
- `marlene` e `jonas` (Cidade Líder);
- `renan` (executor da demo), `paula`, `sergio` e `beatriz` (Vila Leopoldina, em volta do estande — V908).

Transportadoras: `transportadora-dev` e `transportadora-teste` com patrocinador;
`outra-transportadora` sem, que é a fixture do desfecho `SEM_PATROCINIO`.
