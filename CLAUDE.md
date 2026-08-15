# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Omni-Tribo — Memória do Projeto

## O que é

App de missões sociais hiperlocais gamificadas. Usuários recebem missões no bairro (entregas
solidárias, coleta de recicláveis, mutirões, ajuda), fazem check-in geolocalizado e recebem XP, BRL e
tokens. Tese do produto: uma entrega que falhou vira missão comunitária remunerada.
Projeto acadêmico FIAP — Sistemas de Informação, RM 555833.

Reconstrução de um protótipo Flutter descartado. NÃO copie padrões do protótipo: lá distância e valor
eram String, não havia autenticação, e aceitar missão creditava recompensa imediatamente.

## Escopo

Desenvolvimento 100% local. Um Postgres+PostGIS em Docker, backend Spring Boot, app Expo no emulador.
NÃO adicione broker de mensageria, Redis, proxy reverso, Prometheus ou Grafana sem eu pedir — foram
deliberadamente cortados do MVP. Se achar que algum é necessário, me pergunte antes.

## Arquitetura

Monólito modular (ver docs/adr/0001). Raiz do pacote Java: `com.omnitribo` — sem prefixo `br.`.
Um pacote por módulo, `com.omnitribo.<modulo>.{api,dominio,infra}`:
compartilhado · identidade · missoes · geolocalizacao · carteira · logistica · notificacoes · integracoes
Cada um com api/ (controllers, DTOs, portas), dominio/ (entidades, regras), infra/ (repositórios,
clientes). Regra verificada por ArchUnit: módulo só acessa outro por api/ pública ou evento. Nunca
repositório ou entidade JPA alheia. carteira referencia missao_id como UUID puro, sem FK,
deliberadamente.

Maturidade real por módulo (o alvo é o de cima; o de hoje é este):
- Três camadas povoadas: `compartilhado`, `identidade`, `missoes`, `carteira`, `geolocalizacao`.
  `geolocalizacao/api/` são só portas — o endpoint de check-in vive em `missoes`, porque a missão é
  o agregado que a transição pertence.
- Também com três camadas: `logistica` (ponto de custódia), `notificacoes` (caixa de entrada) e
  `integracoes` (clima e CEP — módulo NOVO, ver ADR 0011). Nenhum módulo está vazio hoje.

Módulo só fala com módulo por porta em `api/`. As de hoje:
- `carteira/api/` — `CreditoRecompensa`, `FinanciamentoMissao`, `EstornoPote`,
  `ProvisionamentoCarteira`
- `missoes/api/` — `ConversaoEntregaFalida` (o webhook de transportadora cria a missão de retirada
  por aqui; `logistica` não pode importar `missoes.dominio`)
- `logistica/api/` — `BaixaCustodia` (a contraparte: a conclusão da missão libera a vaga). São DUAS
  classes de serviço em `logistica/dominio` de propósito — juntas fechariam o ciclo de beans
  `MissaoService → EntregaFalidaService → MissaoService`
- `integracoes/api/` — `ConsultaClima` (o webhook alimenta o modelo de risco; devolve `Optional` e
  NUNCA lança, porque provedor externo fora do ar não pode transformar em 5xx o registro de uma
  entrega falida — a transportadora reenviaria em laço. Ver ADR 0022)
- `identidade/api/` — `ProgressaoUsuario` (concede XP, deriva nível e filtra por nível em lote),
  `ConsultaAfiliacao`, `ConsultaConsentimento` (quem pode ser notificado — consulta em MASSA, porque
  `ConsentimentoService.listar` resolve o estado atual em Java e não escala para o fan-out),
  `UsuarioSistema` (o UUID fixo do criador das missões automáticas), `ConsultaSessao` (o `JwtAuthFilter`
  a consulta a cada requisição — é o que faz papel e anonimização serem reconferidos; ADR 0016)
- `geolocalizacao/api/` — `RegistroCheckin` (`missoes` injeta pela INTERFACE, porque é o tipo
  declarado no campo que o ArchUnit inspeciona — injetar a implementação passaria a compilar e
  quebraria o teste de arquitetura)
- `notificacoes/api/` — `DespachoAlerta` (`compartilhado` injeta pela INTERFACE: é isento como
  ALVO, mas continua sendo ORIGEM, e nomear a implementação reprovaria o ArchUnit)
- `compartilhado/api/` — `PublicadorEventos`, `PaginaResponse`, `RecursoAuditavel`,
  `AuditoriaPersistencia`, `ConsultasGeoespaciais`, `EmissorDeToken`, `ControleDeTentativasLogin`,
  `AtributosWebhook` (a chave do atributo onde o filtro HMAC publica a transportadora VERIFICADA —
  o controller lê de lá, nunca do cabeçalho cru),
  `EnderecoDoCliente` (ÚNICO ponto que decide o IP de quem chamou — e **não lê `X-Forwarded-For`**:
  quem resolve proxy é a `RemoteIpValve` do Tomcat, por `server.tomcat.remoteip.trusted-proxies`,
  ausente em dev/test. Ler o cabeçalho direto dava chave nova a cada tentativa e o bloqueio
  progressivo de login nunca acumulava — credential stuffing ilimitado. Ver ADR 0019),
  `TipoProblema` (o catálogo de URIs de erro — uma por REAÇÃO DE UI, ADR 0010),
  `DadosPessoaisDoUsuario` (porta com PLUGINS: cada módulo publica a própria seção da exportação
  LGPD, e quem monta o arquivo não nomeia módulo nenhum — é o que evita o ciclo
  `identidade → missoes → identidade`)

Toda implementação roda `REQUIRED`/`MANDATORY` — **`REQUIRES_NEW` é proibido no caminho de valor**,
porque a transação externa segura `FOR UPDATE` e a interna pediria uma segunda conexão: com N ≥
tamanho do pool, deadlock de pool e 500 para todo mundo. Isso não é teoria — aconteceu no check-in da
F6 e derrubava até o login (ver Notas de manutenção de 2026-08-07).

O schema de TODOS já existe desde V4–V7: o banco está à frente do código. Encontrar tabela sem
código correspondente é o estado esperado, não resíduo. Mesma coisa fora de `services/api/`:
`tools/seed/` (`make seed`) é diretório reservado, hoje vazio. `tools/carrier-mock/` tem
`enviar.sh`, que exercita o webhook contra o servidor de pé, e `tools/dataset/` tem `gerar.sh` mais
os artefatos do modelo de risco (CSV, coeficientes, relatório de métricas).

`RegrasArquiteturaTest` aplica a regra aos 7 módulos de negócio; `compartilhado` fica fora do array
`MODULOS` porque é shared por design. Mas **`compartilhado/infra` tem regra própria** e é fechado a
todo mundo: `dominio` é kernel (livre), `api` é porta (livre), `infra` é adaptador PRIVADO. A
assimetria é medida — há 46 imports legítimos de `compartilhado.dominio` vindos de outros módulos, e
proteger o módulo inteiro deixaria o teste vermelho em quase todo arquivo.

Por isso `ConsultasGeoespaciais`, `EmissorDeToken` (impl `JwtService`) e `ControleDeTentativasLogin`
(impl `BloqueioLoginService`) são **interfaces em `compartilhado/api`**. Antes eram classes concretas
em `infra`, e quatro domínios dependiam da primeira — domínio dependendo de infra, sem teste nenhum
acusando.

**A regra do ArchUnit é DIRECIONAL, e isso restringe o desenho de `compartilhado`.** `compartilhado`
é isento como ALVO, mas suas classes continuam sendo ORIGEM — e mover para `api/` não afrouxa isso:
`ConsultasGeoespaciais` NÃO pode importar `Missao`, `StatusMissao` nem `CategoriaMissao`. Por isso
status e categoria entram como String (sempre `.name()` de um enum já validado pelo binder, nunca
texto livre do cliente) e o retorno é `AlvoProximo`, um par neutro id+distância que o chamador
reidrata. Mesma razão pela qual `EmissorDeToken` recebe `papel` como String.

## Economia (três moedas)

**Quem cria a missão NÃO paga.** Essa é a premissa do produto, e o ADR 0009 a registrou depois de
ela ter sido violada em silêncio pelo ADR 0004. A recompensa é XP + TOKEN, **calculada pelo servidor
e congelada na criação** — o DTO de criação NÃO tem `xpRecompensa` nem `tokensRecompensa`.

`CalculadoraDeRecompensa` (`missoes/dominio`) é função pura: recebe categoria, complexidade,
distância, peso, volume e **multiplicador de risco**, e devolve XP + tokens + complexidade efetiva +
`versaoFormula` + multiplicador aplicado. Calibração em `app.missoes.recompensa.*` — a FÓRMULA é
código, os NÚMEROS são configuração.

**O multiplicador de risco entra na BASE, junto da complexidade — nunca sobre o total.** Multiplicar
o total escalaria também distância, peso e volume, e a recompensa explodiria de forma não linear no
caso extremo. Vem de `PrevisorDeRisco` (`logistica/dominio`), é limitado a **[1,00; 1,50]** e é
CONGELADO em `missao.multiplicador_risco` junto com `versao_formula`. Missão criada por usuário
recebe 1,00 — só o webhook de entrega falida avalia risco. Ver ADR 0022.

**Mudou parâmetro no YAML? Suba `versao` junto.** `CalculadoraDeRecompensaTest.douradoV1` falha de
propósito para forçar a decisão: sem isso, missões antigas passam a ser explicadas por uma calibração
que não as produziu, e some a resposta para "este crédito estava certo quando foi feito?".

**Complexidade: derivada onde há dado.** ENTREGA e COLETA exigem peso e volume, e o servidor deriva —
declarar junto é 400. TRIBO e AJUDA declaram, porque não movem objeto. A conclusão LÊ o congelado,
nunca recalcula. `POST /missoes/previa-recompensa` mostra o valor sem criar nada; o app nunca duplica
a fórmula.

XP: reputação, não transferível, monotônico, sem ledger. Nível é DERIVADO do XP por `RegraNivel`,
nunca incrementado — a coluna `usuario.nivel` é cache recalculado a cada concessão.
TOKEN: moeda comunitária, transferível na mesma tribo. **Recompensa de TODAS as categorias.**
Resgatável em benefício de parceiro do bairro — esse resgate é o sumidouro real (F8+).
BRL: **fora do ciclo de missões.** `ck_missao_economia` (V15) exige `valor_brl = 0` em toda missão, e
`app.carteira.saque-habilitado` é `false` por padrão. Colunas e `SaqueService` permanecem, testados,
como infraestrutura da conversão patrocinada futura — não remova.

Regra: nenhuma missão pode ter valor_brl > 0. Quem tentar recebe 400 apontando o campo.

Conservação do TOKEN: missão TRIBO/COLETA paga o executor a partir de `missao.pote_tokens`, que
membros financiam debitando a própria carteira. Nada é cunhado no ciclo — `SUM(carteira.saldo_tokens)
+ SUM(missao.pote_tokens)` é invariante. Publicar exige pote cobrindo a recompensa (senão a missão
chegaria em AGUARDANDO_CONFIRMACAO sem poder ser concluída); cancelar ou expirar estorna o pote aos
financiadores, senão os tokens ficam presos e a conservação vira mentira.

O estorno tem DOIS pontos de chamada, não um: `MissaoService.aplicar` e
`ExpiracaoMissoesService.expirarUma`. O job de expiração é o único caminho para EXPIRADA e não passa
por `aplicar()` — sem a chamada lá, os tokens ficariam presos numa missão morta e a reconciliação
continuaria respondendo `integro=true`, porque ledger e projeção seguem batendo. A perda seria
invisível justamente para o endpoint que existe para achá-la.

**Todo estado não-terminal precisa de saída que não dependa de um humano específico aparecer.**
`EM_ANDAMENTO` e `AGUARDANDO_CONFIRMACAO` não tinham, e o pote de quem financiou ficava imobilizado
para sempre quando o executor ou o criador sumia. Hoje os dois têm varredura por prazo (`SISTEMA`,
calibrada em `app.missoes.expiracao.prazo-*`) e porta manual (`POST /missoes/{id}/destravar`, só
ADMIN). Os desfechos diferem de propósito: abandono sem check-in → `EXPIRADA` com estorno; omissão
do criador APÓS o check-in → `CONCLUIDA` **pagando o executor**, porque o check-in geolocalizado é a
evidência que o sistema aceita como prova em todo outro caminho.

## Stack

Backend: Spring Boot 4.1 · Java 21 · Maven · PostgreSQL+PostGIS · Flyway · Caffeine · bucket4j
Mobile: Expo SDK 57 · TypeScript strict · Expo Router · TanStack Query · Zustand
Testes: JUnit 5 · Testcontainers · ArchUnit · Jest/RTL/MSW

## Comandos

> `make seed` e `make test` ainda são stubs. Os demais targets (`up`, `down`, `reset`, `logs`, `ps`, `psql`) estão implementados.

Clone novo exige UM passo antes de qualquer `./mvnw verify` ou `spring-boot:run`:

```bash
bash tools/gerar-chaves-dev.sh  # services/api/keys/ é gitignored; sem PEM nenhum contexto Spring sobe
```

O `.env` **não** é passo manual: o Makefile tem um alvo de arquivo `.env`, do qual todo target que
lê o compose depende, então `make up` o cria a partir do `.env.example` sozinho. E nada além do
compose precisa dele — `./mvnw verify` sobe o banco por Testcontainers, e `application-dev.yml` traz
defaults de datasource para o `spring-boot:run`.

```bash
# Backend
cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev  # sobe o servidor local (porta 8080; actuator na 8090)
cd services/api && ./mvnw verify                          # compila + todos os testes + spotless + spotbugs + jacoco
cd services/api && ./mvnw spotless:apply                  # corrige formatação Google Java Format (rodar antes do verify se falhar em formatação)
cd services/api && ./mvnw -Dtest=NomeDaClasseTest test    # um único teste
cd services/api && ./mvnw clean                           # OBRIGATÓRIO depois de renomear migration — ver seção Banco

# Mobile
cd apps/mobile && npm install                             # primeira vez
cd apps/mobile && npm start                               # Metro; leia o QR com o Expo Go
cd apps/mobile && npm run android                         # emulador (exige ANDROID_HOME e um AVD)
cd apps/mobile && npm run typecheck && npm run lint && npm test
cd apps/mobile && npx jest --testPathPattern=nomeDoArquivo
# Integração contra o backend EM EXECUÇÃO — fora do `npm test` de propósito:
cd apps/mobile && E2E_API_URL=http://<ip-da-sua-maquina>:8080 npm run test:e2e
# `localhost` dentro do emulador ou do celular é o próprio aparelho, não o seu PC. A tabela de
# endereços (celular físico · 10.0.2.2 no AVD · web) e a nota de firewall do Fedora estão em
# apps/mobile/README.md — não fixe um IP aqui, ele muda de rede para rede.
# npx expo install <pacote>, NUNCA npm install, para pacotes do ecossistema Expo.

# Infra
make up          # sobe PostgreSQL+PostGIS
make down        # para containers (volume preservado)
make reset       # destrói volume e recria do zero (necessário ao trocar migration de nome)
make logs        # tail nos logs do banco
make ps          # status dos containers
make psql        # abre psql conectado ao banco local
# make seed / make test — ainda não implementados
```

Em dev: Swagger UI em `http://localhost:8080/swagger-ui.html`, OpenAPI em `/v3/api-docs`. Actuator
na porta **8090**, não 8080, com cadeia de segurança PRÓPRIA (`actuatorFilterChain`, `@Order(1)`):
`health` e `info` respondem anônimos — health check que exige JWT não é health check —, `metrics`
continua exigindo autenticação. Sem essa cadeia, o `anyRequest().authenticated()` da cadeia
principal alcança a porta de gestão e `/actuator/health` responde 401.

**A porta de gestão era 8081 e foi movida para 8090 — não a mova de volta.** 8081 é o default do
Metro/Expo, então rodar `npm start` e `spring-boot:run` juntos derrubava o segundo a subir com
`BindException: Endereço já em uso`. O sintoma engana: quem falha é o contexto de management, mas o
Spring aborta a aplicação INTEIRA, e o stack trace não menciona Metro nem Expo em lugar nenhum —
parece build quebrado. A 8081 segue em `app.cors.origens-permitidas` justamente por ser do Expo web.

O `verify` não é só teste: SpotBugs roda com effort `Max`, threshold `Medium` e `failOnError=true`,
então achado de análise estática **quebra o build** como um teste vermelho quebraria. JaCoCo grava o
relatório de cobertura em `services/api/target/site/jacoco/`, publicado como artefato pelo CI, e
**agora também barra**: duas execuções de `check` exigem **80% global** e **85% agregado nos pacotes
`dominio`**, sempre sobre `INSTRUCTION`. **Não troque o contador para `BRANCH`** — branch está em
~75% e o build fecharia vermelho na hora; ela segue no relatório como evidência para ler, e subi-la
é trabalho anterior a ligar qualquer gate sobre ela. Cuidado com o `<includes>` da regra de domínio:
um padrão que não casa nada produz bundle vazio e o check **passa por vácuo** — para conferir, suba
o mínimo para 0,99 e veja se a reprovação cita uma razão real.

A varredura de dependências (OWASP Dependency-Check) fica no profile **`seguranca`**, fora do
`verify` padrão: `./mvnw -Pseguranca verify -Dnvd.api.key=$NVD_API_KEY`. Ela **exige** chave da NVD —
sem ela o plugin aborta com "Invalid API Key, length of 0". Não configure a chave no pom via
`${env.*}`: variável ausente vira string vazia e produz esse mesmo erro.

## Superfície de API hoje

`/api/v1/auth` — `POST registrar` · `POST login` · `POST refresh` · `POST logout` · `GET me`

`/api/v1/missoes` — `GET` (lista paginada com filtro) · `GET /proximas` (radar geoespacial) ·
`POST /previa-recompensa` (calcula sem criar) · `POST`
· `GET /{id}` · `PATCH /{id}`, mais as ações `POST /{id}/{acao}`: `publicar`, `aceitar`, `iniciar`,
`desistir`, `cancelar`, `contestar`, `checkin`, `confirmar`, `resolver` e `destravar` (os dois
últimos só ADMIN).

`/api/v1/carteira` — `GET` (saldo) · `GET /lancamentos` (extrato paginado) · `POST /transferencias`
· `POST /saques`. Os dois POST exigem header `Idempotency-Key`.

`/api/v1/tribos/{triboId}/financiamentos` — `POST`, com `Idempotency-Key`.

`/api/v1/admin/carteiras/reconciliacao` — `GET`, só ADMIN.

`/api/v1/usuarios` — `GET me` (perfil completo: nome, handle, tribo, XP, nível derivado,
conquistas) · `GET me/dados` (exportação LGPD) · `GET|PUT me/consentimentos[/{tipo}]` ·
`DELETE me` (anonimização; exige a senha atual no corpo). **`GET /auth/me` continua existindo e é
outra coisa**: a checagem barata do boot, resolvida só dos claims do JWT. Não a enriqueça: trocaria
essa checagem por uma consulta com joins em toda abertura do app.

`/api/v1/tribos` — `GET` (lista) · `GET /{id}` (com centro geográfico DERIVADO por `ST_Centroid`).

`/api/v1/alertas` — `GET` (paginado, `?apenasNaoLidos`) · `PATCH /{id}/lido` ·
`GET /nao-lidos/contagem`.

`/api/v1/pontos-custodia` — `GET /{id}` · `GET ?lat&lon&raioMetros` (ativos, por distância).

`POST /api/v1/logistica/previsao-falha` — probabilidade de uma entrega falhar, faixa de risco e
`fatoresPrincipais` (os termos que mais pesaram). Regressão logística interpretável, coeficientes em
`app.logistica.risco`. É POST sem escrita: o contexto tem nove campos e espremê-los em query string
poria CEP e peso do destinatário no log de acesso de todo proxy. **Coeficientes treinados em dados
SINTÉTICOS** — ver ADR 0022 e `docs/qualidade/modelo-previsao.md`.

`/api/v1/clima?lat&lon` e `/api/v1/enderecos/{cep}` — provedores EXTERNOS (Open-Meteo, ViaCEP)
atrás da nossa fronteira. Falha do provedor responde **503** com
`type` `servico-externo-indisponivel`, e a reação de UI é ESCONDER o recurso. Ver ADR 0011.
A proteção é composta, nesta ordem: **cache → disjuntor → bulkhead → retry → HTTP** (ADR 0023). O
retry fica POR DENTRO do disjuntor para que uma rajada de tentativas conte como UMA falha; e a
checagem do `{"erro":true}` do ViaCEP fica FORA da região protegida, porque o provedor respondeu 200
— movê-la para dentro faria CEP errado digitado abrir o circuito de um provedor saudável.

`POST /api/v1/webhooks/transportadora` — entrada de entregas falidas. **Único endpoint de escrita
sem JWT**, autenticado por HMAC-SHA256 sobre o CORPO BRUTO (cabeçalhos `X-Transportadora`,
`X-Timestamp`, `X-Assinatura`). Idempotente por `(transportadora, codigoRastreio)`. Ponto lotado
responde **200 com desfecho RECUSADA** — não é erro HTTP, e devolver 4xx faria a transportadora
reenviar em laço contra um ponto que continuará lotado. Ver ADR 0021.

`GET /api/v1/ping`, do `PingController` em `compartilhado`.

## Automação

São **três** hooks em `.claude/hooks/`, declarados em `.claude/settings.json`. Dois deles NEGAM a
operação, e quem não souber que existem vai apanhar de um bloqueio sem entender a causa:

- `checar-segredo.sh` — `PreToolUse`/`Bash`. Só age se o comando contém `git commit`: faz grep no
  `git diff --cached` por chave privada PEM, chave `AIza…` e pares `senha|password|secret|token|
  api_key = "…"`. Não é substituto de revisão manual, é uma segunda barreira.
- `guardar-migration.sh` — `PreToolUse`/`Write`. **Nega** a criação de migration com nome fora de
  `V<N>__<snake_case>.sql`, com seed abaixo de 900, com schema em 900+, com **V9 ou V10** (as
  queimadas), ou com uma versão que já exista no working tree **ou em qualquer ref** — inclusive
  `refs/remotes`, que é como ele pega a colisão entre branches de fases paralelas. É a seção Banco
  deste arquivo, virada bloqueio: prefira `/migration` a escrever o arquivo à mão.
- `formatar-java.sh` — `Stop`. Se há `.java` pendente em `services/api`, resolve `JAVA_HOME`
  (SDKMAN, ou `/usr/lib/jvm/java-21-openjdk` — o `java` do PATH no Fedora é JRE-only) e roda
  `./mvnw -q spotless:apply` ao fim do turno. Ou seja: formatação de Java já não é passo manual
  antes do `verify`. O mobile **não** tem equivalente — lá o Prettier entra pelo ESLint, e
  formatação errada aparece como lint vermelho.

CI (`.github/workflows/`), três workflows:
- `api.yml` — push/PR que toque `services/api/**`. Gera as chaves RSA (`tools/gerar-chaves-dev.sh`)
  ANTES do `./mvnw verify` e arquiva o relatório JaCoCo.
- `mobile.yml` — push/PR que toque `apps/mobile/**`. Node 22, `npm ci`, `typecheck`, `lint`,
  `npm test -- --ci --coverage`, arquiva a cobertura. **Não roda `test:e2e`**, de propósito: aquele
  teste exige o backend de pé.
- `security.yml` — todo push/PR, sem filtro de path. Gitleaks no histórico completo.

## Onde está o quê, na documentação

- `docs/PROGRESSO.md` — tabela de fases e **Notas de manutenção**: o log de por que cada correção
  estrutural foi feita. Primeiro lugar a olhar quando algo neste arquivo parecer arbitrário.
- `docs/auditoria/` — **F0…F7** (backend) e **`mobile-fundacao.md` / `mobile-completo.md`** (app).
  Os dois do mobile não seguem o padrão `FN.md` de propósito: a numeração de fases já usa F8 para
  "Logística, notificações e patrocinador", e um `F8.md` sobre mobile criaria contradição com o
  `PROGRESSO.md`. Uma auditoria por fase, contra a especificação original, com
  evidência EXECUTADA (SQL, `curl`, `EXPLAIN ANALYZE`). Classificam cada item como DEFEITO, LACUNA,
  DIVERGÊNCIA ACEITÁVEL, EXCEDENTE ou CONFORME. É onde está o raciocínio por trás de decisões que
  parecem estranhas — inclusive duas premissas de especificação que foram refutadas com medição.
- `docs/adr/` — decisões com alternativas descartadas. 0001 monólito · 0002 PostGIS · 0003 Expo ·
  0004 três moedas (**tabela de moedas substituída pelo 0009**) · 0005 JWT+Argon2 · 0006 máquina de
  estados · 0007 consultas geoespaciais centralizadas · 0008 ledger append-only e idempotência ·
  **0009 economia do cuidado: TOKEN como recompensa, BRL fora do ciclo** · **0010 granularidade do
  catálogo de tipos de problema: uma URI por REAÇÃO DE UI** · 0011 dependências externas e
  anonimização · **0012 mapa por WebView e Leaflet** · 0013 persistência de segredo por plataforma:
  nada é gravado na web · **0014 expiração com uma transação por missão** (fecha o deadlock e o item
  envenenado) · **0015 destravamento de estados sem saída** · **0016 autorização reconferida por
  requisição** · **0017 papéis de banco separados** (a aplicação não pode alterar o ledger) ·
  0018 fronteira de `compartilhado` · 0019 borda HTTP e cabeçalho não confiável ·
  **0020 ponto de custódia comercial e proximidade por tribo** (por que divergimos dos 50 m do
  brief, e por que "perto" é distância MÍNIMA e não ao centroide) · **0021 verificação de webhook**
  (corpo bruto, carimbo dentro do material assinado, 401 indistinguível, segredo em config) ·
  **0022 previsão de risco de entrega** (regressão logística em Java puro treinada no `verify`,
  dataset sintético, limiar escolhido na validação e não no teste, teto do multiplicador).
  **0023 resiliência das integrações externas** (disjuntor próprio com `Clock` injetado + retry
  nativo do Framework 7; registra a verificação que mostrou o `resilience4j-spring-boot3` 2.4.0 ser
  ANTERIOR ao Boot 4.1, e por que o retry roda POR DENTRO do disjuntor).
  0016–0021 são da verificação de 2026-08-11 e cada um registra a alternativa descartada com o
  motivo MEDIDO — vários deles são a resposta a "por que não fizemos o óbvio?".
- `docs/qualidade/integridade-transacional.md` — evidência de concorrência da carteira (100 threads,
  deadlock, rollback) e a seção "O que esta fase NÃO garante". É o documento a defender oralmente.
- `docs/qualidade/modelo-previsao.md` — métricas do modelo de risco, matriz de confusão, correlações
  injetadas e a discussão falso positivo × falso negativo. **Abre declarando que os dados são
  sintéticos** e fecha com o que a fase não garante. É o outro documento a defender oralmente, e a
  resposta preparada para "sua acurácia é menor que a de um chute?" está nele.
- `docs/seguranca/autenticacao.md` — modelo de ameaça e desenho do fluxo de auth.
- `docs/seguranca/antifraude-geolocalizacao.md` — o que os controles de check-in **não** pegam.
- `docs/evidencias/f6-explain-analyze.md` — saída real do `EXPLAIN ANALYZE` provando uso do índice
  GiST, gerada por `IndiceGeoespacialTest`.
- `docs/evidencias/f12-ciclo-ponta-a-ponta.md` — ciclo completo executado ponta a ponta.
- `docs/INFRA.md` — containers, credenciais de dev, lista completa de usuários seed com tribo.
- `docs/qualidade/` — evidência de build por data (2026-08-05, 08-06 e 08-15; as verificações de
  08-07 e 08-11 estão nas **Notas de manutenção** do `PROGRESSO.md`, não aqui). Também
  `matriz-rastreabilidade.md`: requisito → endpoint/tela → teste → resultado → evidência, **com os
  não implementados e a justificativa de cada um** (§2.3). `docs/diagramas/` vazio.
- `documentacao/` — o PDF da entrega acadêmica. Não é fonte de verdade técnica: envelhece a cada
  fase e não é atualizado junto com o código.
- `CONTRIBUTING.md` — tabela de tipos de Conventional Commit aceitos e checklist pré-commit.

## Skills e agentes disponíveis

- `/verificar` — roda verificação completa (mvnw verify + typecheck + lint + test + docker compose ps) e reporta verde/vermelho. Use antes de abrir PR. **Nunca declare sucesso sem rodar isso.**
  O passo 0 é condicional: se o diff tocou `db/migration` ou `db/seed`, `make reset` vem ANTES do
  resto — ver a seção Banco para o porquê.
- `/adr <assunto>` — cria `docs/adr/NNNN-<slug>.md` com o próximo número. Template exige Alternativas descartadas com motivo real.
- `/migration <assunto>` — cria a migration Flyway já com o número certo da sequência GLOBAL,
  conferindo faixas queimadas, faixa de seed e branches abertas. É o caminho sancionado; escrever o
  arquivo à mão esbarra no hook `guardar-migration.sh`.
- `/commit` — aplica o checklist pré-commit do `CONTRIBUTING.md` e monta a mensagem Conventional
  Commit. Só o usuário dispara (`disable-model-invocation: true`) — não é invocável por mim.
- Agente `auditor` — audita uma fase contra a especificação e entrega relatório em
  `docs/auditoria/FN.md`. **Não altera arquivo do projeto.** Regra central: medir antes de afirmar.
- Agente `revisor-seguranca` — revisa autenticação, autorização, endpoints de valor, webhooks, dados pessoais. Checar após implementar qualquer um desses.
- Agente `revisor-testes` — avalia se a suíte realmente garante comportamento (não conta testes, avalia o que cobrem). Rodar ao fechar fase.

## Convenções por camada

São **três** arquivos `CLAUDE.md`, e os dois aninhados entram em contexto sozinhos quando se
trabalha naquele diretório — por isso o detalhe de cada camada vive lá, e não aqui:

| Arquivo | Cobre | Quando carrega |
|---|---|---|
| `CLAUDE.md` (raiz) | produto, arquitetura, economia, banco, segurança, estado, pendências | toda sessão |
| `services/api/CLAUDE.md` | convenção e armadilha de backend: transação, lock, idempotência, erro, teste de integração, Jackson 3, AOP, MockMvc | ao mexer em `services/api/` |
| `apps/mobile/CLAUDE.md` | estrutura do app, economia vista pela UI, discriminação de erro por `type`, armadilhas do jest-expo | ao mexer em `apps/mobile/` |

O que é regra transversal — banco, segurança, teste, git — fica nas **Regras não negociáveis**
abaixo, porque vale nos dois lados.

## Regras não negociáveis

Versões

- NUNCA escreva número de versão de memória. Verifique no Maven Central, npm ou start.spring.io.
  Se não conseguir verificar, pare e pergunte.
- No mobile use `npx expo install`, nunca `npm install` direto, para pacotes do ecossistema Expo.

Banco

- Flyway é a ÚNICA fonte de schema. ddl-auto é sempre validate. Nunca resolva divergência mudando
  ddl-auto — escreva migration.
- **Versão de migration é sequência GLOBAL, não por diretório.** Duas faixas, separadas de propósito:
  - `db/migration` — schema, **V1–V8 e V11–V22**; único location do perfil default/prod.
    Próxima é **V23**. **V9 e V10 estão queimadas — nunca as reutilize.** Foram os arquivos de seed
    antes da renomeação para `V900__seed_dev.sql`, então um banco de dev criado antes dela tem as
    versões 9 e 10 gravadas no `flyway_schema_history` com descrição de seed. Um `V9__*.sql` novo em
    `db/migration` passaria em clone novo e falharia em máquina antiga com erro de checksum ou
    "detected applied migration not resolved locally" — divergência que não aparece no CI.
  - `db/seed` — só dev e test (via `application-dev.yml` / `application-test.yml`), faixa **900+**.
    Hoje são cinco: `V900__seed_dev.sql`, `V901__seed_entregas_falidas.sql`,
    `V902__seed_alertas_consentimentos.sql`, `V903__seed_cidade_lider.sql` (dados de demonstração
    na zona leste — ver docs/INFRA.md) e `V904__seed_entrega_falida_fixtures.sql` (ponto LOTADO e
    os dois únicos usuários com NOTIFICACAO+LOCALIZACAO vigentes, sem os quais dois caminhos do
    webhook não têm fixture). **Próximo seed é V905.**
  - A faixa 900+ garante por construção que o seed roda depois de todo schema. Seed novo continua na
    faixa e NUNCA usa um número que o schema possa alcançar. Ver ADR 0006, Notas de manutenção.
  - Como o seed é o último, ele grava dados em forma final: não conte com migration posterior para
    corrigir valor de seed.
  - **Fases em paralelo devem reservar faixas disjuntas.** F5 pulou de V11 para V13 exatamente para
    não colidir com a V12 da branch de geolocalização — duas migrations com a mesma versão derrubam
    o merge com *"more than one migration with version N"*. Antes de escolher o número, olhe as
    branches abertas, não só `db/migration` local.
  - **Consequência de ter o seed em V900: toda migration nova exige `make reset` num banco de dev já
    existente.** Como V900 já está aplicada, qualquer V12/V13/V15 nova tem versão MENOR que o topo do
    histórico e o Flyway a classifica como *out-of-order* — que `application-dev.yml` mantém
    desligado. O sintoma é o `spring-boot:run` morrer no boot com `Validate failed: Detected resolved
    migration not applied to database: 12`, sem nenhuma menção a seed ou a ordenação. Não tente
    resolver com `out-of-order: true`: isso deixaria o schema de dev divergir da ordem que prod
    aplicaria. `make reset` é a resposta, e o custo é zero porque o seed reconstrói os dados.
  - **Ao RENOMEAR uma migration, rode `./mvnw clean`** (além do `make reset`). O Maven não remove de
    `target/classes` o arquivo com o nome antigo, então o Flyway acha os dois e aplica os dois — o
    sintoma é `duplicate key value violates unique constraint`, que não parece ter relação nenhuma
    com renomear arquivo. CI não sofre disso: clona do zero.
- Dinheiro: numeric(12,2) → BigDecimal. Tokens: bigint. Nunca double, nunca String.
- Coordenada: geography(POINT,4326). Distância é derivada por PostGIS, nunca armazenada. `ST_DWithin`
  sobre `geography` recebe o raio em METROS e `ST_Distance` devolve METROS — nenhuma conversão de
  unidade acontece em Java.
- Extensões `postgis` e `pgcrypto` são habilitadas via `docker/init/01-extensions.sql` e `V1__extensoes.sql`. `pgcrypto` provê `gen_random_uuid()` no banco.
- timestamptz, nunca timestamp. Enum: varchar + CHECK + EnumType.STRING, nunca ordinal.
- lancamento, auditoria e checkin são APPEND-ONLY. Correção por ESTORNO, nunca UPDATE.

Segurança

- Nenhum segredo em arquivo versionado. Só ${VARIAVEL}, com .env.example commitado.
- Identidade do usuário vem SEMPRE do JWT. Nunca do corpo, query ou header.
- Controller nunca recebe nem devolve entidade JPA. Sempre DTO/record.
- SQL sempre com parâmetro bindado, inclusive nas queries PostGIS. Zero concatenação.
- Erro é RFC 9457 ProblemDetail. Nunca stack trace, SQL, nome de classe ou mensagem de driver.
- Nunca logue senha, token, refresh, coordenada exata ou payload de requisição autenticada.
- Mobile: credencial em expo-secure-store. NUNCA AsyncStorage. O acesso passa por
  `src/lib/armazenamentoSeguro.ts`, nunca pela lib direto — ela não tem implementação web e estoura
  no boot do browser. Na web nada é persistido: `localStorage` gravaria em claro o mesmo refresh de
  30 dias que a regra acima existe para proteger. Ver ADR 0013.
- Validação geoespacial e de saldo é SEMPRE no servidor. Valor calculado no cliente é ignorado.
- **Chave de idempotência do cliente nunca é armazenada crua quando a UNIQUE é global.** O check-in
  guarda `sha256(usuario|missao|chave_do_cliente)`: com a chave crua, o cliente que manda `"1"`
  receberia o replay do check-in alheio. Ver `ChaveIdempotencia`.
- HMAC de webhook é sobre o CORPO BRUTO, não o objeto desserializado, comparado em tempo constante.
- Deep link é entrada não confiável: valide esquema, host e formato antes de navegar.
- Transferência entre carteiras trava as duas em ordem determinística (ordene por id da carteira),
  sob pena de deadlock.

Testes

- Todo endpoint novo nasce com teste de caminho feliz e de erro. Fase sem teste verde não está pronta.
- Integração usa Testcontainers com PostGIS real. Nunca H2 para geoespacial.
- Operação de valor (aceite, crédito, transferência, saque, check-in) exige teste de concorrência
  multi-thread.
- Não escreva teste sem assertion para subir cobertura.
- Quando um teste de integração acusa bug, corrija o CÓDIGO. Os dois bugs de cinemática da F6
  (truncamento de `Duration.toSeconds()` e estouro de `NUMERIC(10,2)`) foram achados assim e
  corrigidos no cálculo, não na assertion.

Git

- Conventional Commits. Uma branch por fase: feat/f6-geolocalizacao. Nunca commite na main direto.
- NUNCA git push --force nem git reset --hard sem eu pedir explicitamente.
- Antes de commitar, confira que não há segredo no diff.
- **Merge de duas branches de fase que tocam o mesmo serviço exige `./mvnw verify` depois do merge,
  antes do push.** Resolver conflito em construtor de serviço é onde isso quebra: as duas versões
  compilam isoladas, e o resultado do merge pode manter os corpos de métodos de um lado e o
  construtor do outro. Foi o que aconteceu em `develop` (ver Pendências conhecidas).

## Como trabalhar comigo

- Tarefa não trivial: planeje primeiro, mostre o plano, espere aprovação.
- Não diga "pronto" sem ter EXECUTADO o comando de verificação e colado a saída real.
  Compilar não é testar. Teste passando não é feature funcionando.
- Se um teste falhar, não relaxe a assertion nem adicione @Disabled. Corrija o código ou me explique
  por que a expectativa estava errada.
- Se meu pedido é ambíguo, contradiz este arquivo, ou você acha a abordagem ruim: diga antes de codar.
- Comente o PORQUÊ, não o quê — especialmente em segurança e concorrência. Preciso poder defender
  esse código oralmente numa banca.
- Português nos nomes de domínio (Missao, Carteira, StatusMissao) e nas mensagens ao usuário.
  Inglês nos termos técnicos consagrados (Repository, Service, Controller, Dto).
- Decisão arquitetural relevante gera ADR em docs/adr/.

### Modo auditoria

Quando eu pedir para AUDITAR uma fase, o modo é outro: **não altere nenhum arquivo do projeto.** A
entrega é um relatório em `docs/auditoria/FN.md`, e só ele.

- Classifique cada item como **DEFEITO**, **LACUNA**, **DIVERGÊNCIA ACEITÁVEL**, **EXCEDENTE** ou
  **CONFORME**, sempre com arquivo e linha.
- **Meça antes de afirmar.** Rode SQL contra o banco de pé, `curl` contra a API em execução, e os
  testes. Vários achados das auditorias F0–F7 eram invisíveis na leitura do código: o oráculo de
  tempo no login (~6 ms contra ~68 ms), o `REVOKE` inerte porque a aplicação conecta como dono das
  tabelas, e o comentário que afirmava uma defesa inexistente. Ler o código teria confirmado o
  comentário.
- Se um item da especificação estiver tecnicamente errado, **diga**, com o raciocínio — não acomode.
  Ex.: "404 vaza existência" está invertido; quem vaza é o 403.
- Termine com ordem de correção por impacto, e PARE. Corrigir é tarefa separada, e eu decido quando.

## Estado atual

**Backend fechado até F7 e auditado fase a fase, F8 implementada, mobile F9–F12 implementadas.**
Build verde, 0 falhas, SpotBugs limpo. As duas auditorias do mobile acharam **dois defeitos que uma
revisão comum deixou passar** — a aba de missões gastando o prompt de permissão sem justificativa, e
a conta anonimizada continuando a escrever por 15 minutos. **Os dois estão corrigidos** (o segundo na
verificação de 2026-08-11 — ver Pendências). Detalhe por fase em `docs/PROGRESSO.md`.

**O histórico do git engana na numeração das fases**: o commit "F8 - Fundação Mobile" entregou, na
verdade, F9–F11, e a branch `feat/f13-previsao-risco-entrega` entregou **F12c** — F13 ("Entrega
final") continua PENDENTE. `docs/PROGRESSO.md` tem a numeração correta — não infira fase do
`git log`.

**F8 — "Fim da Entrega Falida".** O webhook de transportadora, autenticado por HMAC sobre o corpo
bruto (ADR 0021), converte entrega falida em missão de retirada ABERTA no ponto de custódia
(ADR 0020): valida vaga sob `FOR UPDATE`, incrementa ocupação, congela recompensa em XP+TOKEN,
notifica por tribo com consentimento e teto por hora, e dá baixa na custódia quando a missão conclui.
`tools/carrier-mock/enviar.sh` exercita o caminho feliz e os cinco negativos contra o servidor de pé.
**De F8 falta só o patrocinador** — ver Pendência #1.

**Mobile: F9 a F12 implementadas** em `apps/mobile/` — 11 telas mais a rota-porta `app/index.tsx`
(um `<Redirect>` que decide entre onboarding, `(auth)` e `(tabs)` durante a renderização, não num
`useEffect`), design system, sessão com access token só em memória e refresh em
`expo-secure-store`, rotas `(auth)`/`(tabs)`/`(app)` protegidas — toda tela autenticada fora das
abas vive em `(app)/` —, radar geoespacial e carteira. O detalhe está em `apps/mobile/CLAUDE.md`,
que carrega sozinho ao trabalhar lá. O catálogo de erro foi ampliado antes da primeira tela — ver
**ADR 0010**.

Módulo `missoes`: 9 estados e **17** transições em `StatusMissao` + `MissaoStateMachine` (ADR 0006),
aceite com lock pessimista, radar de proximidade com cache, expiração por `@Scheduled`.

**F12c — previsão de risco de falha de entrega.** Regressão logística interpretável em Java puro
(`logistica/dominio`), treinada dentro do `./mvnw verify` sobre dataset sintético de 5.000 registros
com correlações injetadas e documentadas. O score vira três coisas: multiplicador congelado da
recompensa em TOKEN (teto 1,5×), prioridade no fan-out (`alerta.prioridade`, com carve-out no teto
por hora para risco ALTO), e aviso acionável no detalhe da missão. `POST /logistica/previsao-falha`
devolve probabilidade, faixa e os fatores que mais pesaram. **Os dados são sintéticos e isso está
declarado em todo lugar** — validação com dados reais é o próximo passo (ADR 0022).

`CONCLUIDA` continua sendo o ÚNICO estado que credita — a regra que o protótipo descartado violava.

O que os controles de check-in **não** pegam está em `docs/seguranca/antifraude-geolocalizacao.md`:
spoofing com root/emulador é mitigável e não eliminável, `mocked` é reportado pelo cliente, presença
não é execução, conluio não é detectado, e a cinemática é cega no primeiro check-in de cada conta.

`develop` carrega o merge de duas fases (carteira e geolocalização) que chegou quebrado — construtor
de uma branch com corpos de método da outra — e foi consertado na auditoria de 2026-08-07.

Contagem de testes e evidência de build NÃO ficam neste arquivo — envelhecem a cada PR. Fonte:
docs/PROGRESSO.md e docs/qualidade/.

Usuários seed (perfis `dev` e `test`, carregados via `db/seed/V900__seed_dev.sql`, senha `Senha@123`):
`admin@omnitribo.dev` (ADMIN) · `alice` e `carol` (USUARIO, Tribo Pinheiros e Vila Madalena) · `bob`, `diana`, `erik` (USUARIO). Ver docs/INFRA.md para lista completa com tribos.

## Pendências conhecidas

Seção para armadilhas diagnosticadas e ainda não corrigidas. Ao resolver uma, remova-a daqui.

> **Quatro saíram na verificação de 2026-08-11** (branch `chore/verificacao-backend`):
> - conta anonimizada escrevendo por 15 min — o `JwtAuthFilter` consulta `ConsultaSessao` a cada
>   requisição (cache de 60 s) e monta o principal do BANCO, o que também faz `papel` ser reconferido;
> - `nivel` divergente na exportação LGPD, que passou a derivar por `RegraNivel`;
> - o `REVOKE` inerte: a aplicação agora conecta como `omnitribo_app` e o Flyway tem credencial
>   própria com DDL. `MigracaoTest.aplicacao_nao_consegue_apagar_nem_alterar_o_ledger_em_runtime`
>   prova em runtime (SQLState 42501), não mais só lendo o catálogo;
> - `EM_ANDAMENTO` e `AGUARDANDO_CONFIRMACAO` sem saída — ver a máquina de estados, que agora tem
>   **17 transições** e varredura por prazo mais porta de ADMIN.

**1. ENTREGA e AJUDA ainda CUNHAM token, até a carteira de patrocinador.** `pagaTokensDoPote`
cobre só TRIBO e COLETA, então a conservação
`SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)` vale para essas duas, não para o sistema
inteiro.

Com o webhook em pé, a lacuna ficou mais VISÍVEL e não mais grave: cada entrega falida convertida
cunha tokens. O caminho de fechamento já está montado — `valor_ofertado_brl` é gravado em
`entrega_falida` e a mecânica de pote existe em `FinanciamentoMissao`; falta a carteira do
patrocinador debitar de fato.

**O multiplicador de risco (F12c) AMPLIA essa cunhagem, de forma limitada e deliberada.** Uma entrega
de risco alto cunha até 1,5× o que cunharia — e é exatamente por causa desta pendência que o teto é
estreito e existe em dois blocos de configuração, com `CoerenciaTetoRiscoTest` travando a
concordância. Sem teto, o risco multiplicaria a emissão sem financiador. Quando a carteira de
patrocinador existir, `pagaTokensDoPote` passa a valer para ENTREGA e o teto pode ser reavaliado.

**Isto não foi contornado de propósito, e a razão importa.** Exigir pote para ENTREGA hoje faria
membros da tribo custearem a logística do varejista — o inverso do modelo. O financiador correto
dessas categorias é o PATROCINADOR: entrega que falhou custa re-entrega, armazenagem e risco de
perder o cliente, então patrocinar o pote sai mais barato que o fracasso. É esse o caso de negócio
do challenge. Preferimos uma lacuna documentada a uma regra errada codificada. Fecha na F8, quando a
carteira de patrocinador financiar o pote pela mecânica que já existe (`FinanciamentoMissao`), e aí
`pagaTokensDoPote` passa a valer para todas as categorias.

**2. Missão de entrega falida só conclui pela varredura de prazo.** O criador dela é o
usuário-sistema (`status = 'INATIVO'`, nunca autentica), e `CONFIRMAR` exige `AtorEsperado.CRIADOR`
— que compara IDENTIDADE, não papel. Nenhum humano pode confirmar, nem um ADMIN.

Na prática o desfecho é correto e já projetado: `EXPIRAR_CONFIRMACAO` conclui **pagando o executor**,
porque o check-in geolocalizado é a evidência que o sistema aceita. Mas o executor espera
`app.missoes.expiracao.prazo-confirmacao` para receber, em vez de ser pago ao entregar. As saídas
plausíveis são um segundo endpoint de webhook em que a transportadora confirma o recebimento pelo
destinatário (ela é a contraparte real), ou autoconfirmação no check-in para missões de origem
SISTEMA. **Não decida isso sozinho** — muda quando o token é cunhado.

**3. Transferência exige digitar um UUID.** Não existe endpoint que liste membros da tribo, então a
tela pede o identificador do destinatário como texto. Funciona e é inutilizável na prática.
**Não é esquecimento**: o javadoc de `identidade/api/TriboController` documenta a omissão como
decisão de privacidade —
listar membros daria a qualquer autenticado um mapa social do bairro. A saída não é expor a lista;
é algo como busca por handle exato ou convite. Não decida isso sozinho.
