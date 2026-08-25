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

### Fora de escopo, decidido

Estes dez foram avaliados e recusados. A linha diz o MOTIVO, não só a proibição: sem ele a decisão
é refeita do zero a cada fase, e a recusa vira esquecimento aos olhos de quem chega depois.

- **Cotação token→real.** O patrocinador aporta em TOKEN, e o valor que a transportadora oferta
  entra na fórmula de recompensa como INSUMO DE CALIBRAÇÃO (`tokens-por-real-ofertado`, fórmula
  versão 3), para ordenar missões por urgência — não é câmbio. A relação é unidirecional: nenhum
  ator compra token com dinheiro, e token não é resgatável em reais. O que está fora de escopo é a
  cotação inversa, token→real: token conversível *é* dinheiro, com KYC e enquadramento regulatório
  junto (ADR 0009 §6). Contraexemplo conhecido: a conversão 1:2 do seed (ADR 0009) é migração única
  de saldo legado, não taxa vigente.
- **Pagamento real, KYC e CNPJ de patrocinador.** O cadastro é por endpoint ADMIN. Onboarding com
  validação de CNPJ, meio de pagamento e prevenção a lavagem é produto financeiro, não MVP
  acadêmico: é a mesma obrigação regulatória que o item acima existe para manter fora.
- **Push remoto (FCM/APNs).** O módulo `notificacoes` é caixa de entrada in-app e **resolve o
  requisito** — o alerta chega, é lido e é contado. Push remoto custaria o *development build*, e o
  projeto roda no Expo Go pelo QR: é o mesmo bloqueio que o ADR 0012 já pagou no mapa, e pagá-lo de
  novo troca o caminho de demonstração inteiro por um canal de entrega. `dispositivo.push_token` e
  `dispositivo.plataforma` (V2) ficam INERTES de propósito e **não são pendência** — a tabela
  permanece porque `docs/diagramas/arquitetura-alvo.md` mantém push como alvo, e aquele arquivo
  descreve o que não existe.
- **Testes de carga distribuídos, SLO formal e tuning de pool sem medição.** Número de desempenho
  que ninguém mediu é afirmação indefensável numa banca, e mexer no pool "por segurança" muda o
  comportamento sob concorrência sem nenhum antes-e-depois para comparar. A medição local **foi
  feita em 2026-08-25** (`docs/evidencias/f21-carga.md`, k6, três cenários, sem afinar um parâmetro
  sequer) — o que segue fora de escopo é a bancada distribuída e o SLO contratual. E o pool continua
  intocado: o rate limit barrou antes dele, então **não há medição do esgotamento do pool** e mexer
  nele continua sendo mudança sem antes-e-depois.
- **Mutation testing no projeto inteiro.** Restrito a `missoes.dominio` e `carteira.dominio`, e sem
  gate: é ali que o teste protege dinheiro e máquina de estados, e é ali que um teste sem assertion
  passaria despercebido. Rodar no projeto todo custa tempo de build por mutante equivalente em
  DTO e getter, e um gate reprovaria o build por eles. **Implementado em 2026-08-25** no profile
  `mutacao` (`./mvnw -Pmutacao test-compile org.pitest:pitest-maven:mutationCoverage`), fora do
  `verify`. **Não restrinja `targetTests`**: filtrar por `missoes.*`/`carteira.*` faz `AporteService`
  — o único ponto de emissão de token — sair como NO_COVERAGE, porque quem o testa é
  `PatrocinadorAdminTest`, em `identidade.api`. É o `<includes>` vazio do JaCoCo de novo.
- **Dark mode.** Dobraria a auditoria de contraste da F12: os 22 pares texto/fundo virariam 44, e
  11 dos 22 já reprovaram em WCAG AA uma vez. `userInterfaceStyle: 'light'` está fixado em
  `app.config.ts` justamente para que a auditoria valha para o que o usuário vê.
- **Ilustrações personalizadas e família tipográfica custom.** Asset autoral e licença de fonte não
  movem requisito nenhum, e fonte embarcada ainda pesa no bundle e some do fallback web. O sistema
  de design entrega hierarquia com a fonte do sistema, que já passou contraste e tamanho.
- **Certificação iOS/VoiceOver.** Não há Mac nem iPhone aqui — certificar exigiria hardware que o
  projeto não tem. A verificação de acessibilidade é em TalkBack, no Android, e **nenhuma passada
  está registrada até hoje**: é a LACUNA L4 da auditoria mobile, planejada para a F18. O que não
  for verificado continua declarado como não verificado — afirmar suporte que ninguém executou é
  pior que a lacuna, porque impede que alguém vá conferir.
- **Recalibração do modelo de risco e validação com dado real.** Não existe operação, logo não
  existe entrega falida real para treinar — recalibrar sobre mais dado sintético só aumentaria a
  confiança num número sem melhorar a previsão. Segue registrada como o próximo passo do ADR 0022,
  e não como trabalho desta entrega.
- **Internacionalização, Detox e Maestro.** O produto é hiperlocal, de um bairro, com domínio em
  português até nos nomes de classe: i18n adicionaria uma camada de indireção em toda string para
  um segundo idioma que não existe. Detox e Maestro exigem build nativo e aparelho no CI — o mesmo
  bloqueio do Expo Go —, e o ciclo ponta a ponta já é exercitado por `test:e2e` contra o backend
  de pé.

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
- `carteira/api/` — `CreditoRecompensa`, `FinanciamentoMissao` (o `debitarPatrocinador` devolve
  `Optional` VAZIO em saldo insuficiente, em vez de lançar: a encomenda já está na loja e a recusa
  precisa ser GRAVADA), `EstornoPote`, `ProvisionamentoCarteira`,
  `AporteToken` (o ÚNICO ponto de emissão de token do sistema — ver Economia)
- `missoes/api/` — `ConversaoEntregaFalida` (o webhook de transportadora cria a missão de retirada
  por aqui; `logistica` não pode importar `missoes.dominio`), `ConfirmacaoRetirada` (a contraparte:
  a transportadora confirma o recebimento e a missão conclui pagando o executor — ADR 0026)
- `logistica/api/` — `BaixaCustodia` (a contraparte: a conclusão da missão libera a vaga). São DUAS
  classes de serviço em `logistica/dominio` de propósito — juntas fechariam o ciclo de beans
  `MissaoService → EntregaFalidaService → MissaoService`
- `integracoes/api/` — `ConsultaClima` (o webhook alimenta o modelo de risco; devolve `Optional` e
  NUNCA lança, porque provedor externo fora do ar não pode transformar em 5xx o registro de uma
  entrega falida — a transportadora reenviaria em laço. Ver ADR 0022)
- `identidade/api/` — `ConsultaPatrocinador` (slug da transportadora → titular de carteira ATIVO;
  `logistica` a consulta no webhook. Devolve vazio para inexistente, inativo E desconhecido, de
  propósito — os três dão no mesmo desfecho e distingui-los vazaria estado financeiro de terceiro),
  `ProgressaoUsuario` (concede XP, deriva nível e filtra por nível em lote),
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

Conservação do TOKEN: quem paga do pote é decidido por **`missao.fonte_pote`**, congelada na
criação — não pela categoria (ADR 0024). `COMUNIDADE` (TRIBO/COLETA) tem pote financiado por membros;
`PATROCINADOR` (entrega falida) tem pote financiado pela transportadora na própria conversão;
`CUNHAGEM` emite na conclusão e, desde o ADR 0025, é só ENTREGA criada por humano — AJUDA passou a
pagar do pote como TRIBO, porque o argumento que a mantinha fora ("vizinhos custeando logística de
varejista") descreve ENTREGA e nunca foi sobre ela.

**A cunhagem não sumiu — mudou de lugar, e é isso que a torna defensável.** O único ponto de emissão
é `APORTE_PATROCINADOR`, por endpoint ADMIN, auditado e idempotente.

**A economia é um CICLO, não um estoque** (ADR 0027). O enunciado exato da invariante, e ele tem duas
partes que não podem ser encurtadas numa:
`SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)` é constante **dentro do ciclo de missões**,
nas quatro categorias — e muda nas DUAS pontas: **sobe** no `APORTE_PATROCINADOR` (emite) e **desce**
no `RESGATE` (queima). Nenhuma outra operação a altera; todas as demais movem token de lugar.
Dizer só "a soma é constante" descreve um estoque fechado, que nunca foi o desenho. Antes da V23 a emissão acontecia na CONCLUSÃO de toda
ENTREGA e AJUDA, implícita, por missão e invisível para a reconciliação — ledger e projeção batem
quando se cria token do nada.

Publicar exige pote cobrindo a recompensa (senão a missão chegaria em AGUARDANDO_CONFIRMACAO sem
poder ser concluída); cancelar ou expirar estorna o pote aos financiadores, senão os tokens ficam
presos e a conservação vira mentira. **O estorno enxerga os DOIS motivos de financiamento**
(`FINANCIAMENTO_TRIBO` e `FINANCIAMENTO_PATROCINADOR`) — `LancamentoRepository
.buscarFinanciamentosDaMissao` filtra por motivo, e um motivo novo que não entre naquela lista deixa
o dinheiro preso numa missão morta com a reconciliação respondendo `integro=true`.

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

> `make test` roda `./mvnw verify` + `npm test`. `make seed` **não executa nada de propósito**: o
> seed são migrations Flyway na faixa V900+, já aplicadas no boot dos perfis dev e test — o alvo só
> explica isso e aponta `make reset`. Os demais (`up`, `down`, `reset`, `logs`, `ps`, `psql`) estão
> implementados.

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

`/api/v1/beneficios` — `GET` (catálogo paginado; por proximidade `?lat&lon&raioMetros` OU por
`?triboId`, nunca os dois). Só benefício ativo de parceiro ativo. A distância vem do PostGIS a cada
consulta e é nula no recorte por tribo.

`/api/v1/resgates` — `POST`, com `Idempotency-Key`. **É o SUMIDOURO do TOKEN**: o lançamento debita
com motivo `RESGATE` e NÃO credita ninguém — sem contraparte, sem missão. Devolve um código de
retirada de 8 caracteres que **não é credencial** (quem autoriza a baixa é o ADMIN, pelo id). Ver
ADR 0027.

`/api/v1/admin/beneficios` — `POST`, só ADMIN. Benefício é `BEM` ou `PERCENTUAL`, **nunca em reais**:
a borda reprova com 400 e `ck_beneficio_sem_reais` (V24) é a barreira final. Preço em moeda corrente
publicaria a cotação token→real que o ADR 0009 §6 recusa.

`/api/v1/admin/resgates/{id}` — `PATCH`, só ADMIN. `PENDENTE → UTILIZADO`, idempotente. **Sem caminho
de volta**: reverter ressuscitaria token queimado.

`/api/v1/admin/impacto` — `GET`, só ADMIN. **A única resposta do sistema sobre VALOR**, e não sobre
estado: funil da entrega falida (recebidas → convertidas → concluídas), tempo mediano até o check-in
do executor, custo evitado estimado e circulação do token. Tudo agregado na hora — **sem migration,
sem tabela de agregação e sem cache**, porque uma segunda fonte de verdade para números que existem
para serem conferidos é pior que a consulta a mais. Mora em `compartilhado` e compõe uma porta nova
de cada módulo dono do dado. Três coisas que o painel diz em voz alta e o código trava por teste:
`app.impacto.custo-reentrega-brl` é **PREMISSA, não medição** (por isso a resposta ecoa o valor e
traz a mesma conta com ele em **±50%**); **"re-entrega evitada" é a missão concluída RENOMEADA**, não
uma segunda medição; e **taxa com denominador zero é `null`, nunca 0%**. Ver ADR 0029.

`/api/v1/admin/carteiras/reconciliacao` — `GET`, só ADMIN.

`/api/v1/admin/patrocinadores` — `POST` (cadastra titular + carteira + relação com o slug) · `GET`
(lista, SEM saldo de propósito) · `POST /{id}/aportes` (**EMITE token**; exige `Idempotency-Key`) ·
`DELETE /{id}` (encerra sem apagar). Todos só ADMIN. Ver ADR 0024.

`/api/v1/usuarios` — `GET busca?handle=` (acha um vizinho pelo `@` EXATO, só na MESMA tribo de quem
pergunta; devolve id, handle, nome e tribo). **Não há listagem de membros e não haverá** — daria um
mapa social do bairro, e como a transferência é restrita à tribo, uma lista de alvos. Inexistente,
de outra tribo e conta inativa respondem o MESMO 404, indistinguíveis. Teto PRÓPRIO de 12/min
(`app.rate-limit.busca-handle-por-minuto`): o endpoint é oráculo de existência por natureza, e o teto
é o que impede colheita em massa. Ver ADR 0028.

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

`POST /api/v1/webhooks/transportadora/confirmacao` — a transportadora confirma que a encomenda
chegou ao destinatário, e o executor é creditado NA HORA. Mesmo HMAC, mesma idempotência por
`(transportadora, codigoRastreio)`. Existe porque o criador da missão de retirada é o
usuário-sistema e `AtorEsperado.CRIADOR` compara IDENTIDADE — nenhum humano confirma, nem ADMIN.
**A varredura de prazo continua** como rede de segurança para quando a transportadora não confirma.
Rastreio desconhecido ou entrega que nunca virou missão é **404**, não 200: ao contrário do ponto
lotado, aqui não há fato novo a gravar. Ver ADR 0026.

`POST /api/v1/webhooks/transportadora` — entrada de entregas falidas. **Único endpoint de escrita
sem JWT**, autenticado por HMAC-SHA256 sobre o CORPO BRUTO (cabeçalhos `X-Transportadora`,
`X-Timestamp`, `X-Assinatura`). Idempotente por `(transportadora, codigoRastreio)`. **Três desfechos, todos 200:** `CONVERTIDA`,
`RECUSADA` (ponto lotado) e `SEM_PATROCINIO` (sem patrocinador ativo ou sem saldo para o pote).
Existe um QUARTO estado na tabela que o webhook **não** produz: `missao_id` nulo *e* `motivo_recusa`
nulo — encomenda na custódia que nunca virou missão. É o formato do seed V901 (16 das 28 linhas do
banco de dev), e o painel de impacto o conta como `pendentes` em vez de deixá-lo sumir num resto.
Nenhum é erro HTTP — devolver 4xx faria a transportadora reenviar em laço contra uma condição que o
reenvio não muda. Ver ADR 0021 e ADR 0024.

`GET /api/v1/ping`, do `PingController` em `compartilhado`.

## Automação

São **quatro** hooks em `.claude/hooks/`, declarados em `.claude/settings.json`. Dois deles NEGAM a
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
  antes do `verify`.
- `formatar-mobile.sh` — `PostToolUse`/`Write|Edit`. Roda o Prettier local no arquivo do mobile que
  acabou de ser escrito (`.ts/.tsx/.js/.jsx/.json` dentro de `apps/mobile`, fora de `node_modules`).
  É por ARQUIVO, e não por turno como o de Java, porque o Prettier num arquivo custa milissegundos e
  `prettier --write .` reformataria arquivo que a tarefa não tocou. Fecha a assimetria antiga: no
  mobile o Prettier entra pelo ESLint, então formatação errada só aparecia como lint vermelho
  depois, longe da edição que a causou.

CI (`.github/workflows/`), três workflows:
- `api.yml` — push/PR que toque `services/api/**`. Gera as chaves RSA (`tools/gerar-chaves-dev.sh`)
  ANTES do `./mvnw verify` e arquiva o relatório JaCoCo.
- `mobile.yml` — push/PR que toque `apps/mobile/**`. Node 22, `npm ci`, `typecheck`, `lint`,
  `npm test -- --ci --coverage`, arquiva a cobertura. **Não roda `test:e2e`**, de propósito: aquele
  teste exige o backend de pé.
- `security.yml` — **dois jobs, com cadências deliberadamente diferentes.** `gitleaks` roda em todo
  push/PR, sem filtro de path, sobre o histórico completo (`fetch-depth: 0`) — é ele o candidato a
  status obrigatório. `dependencias` (OWASP Dependency-Check) roda só em `schedule` semanal e
  `workflow_dispatch`: CVE novo é publicado pela NVD de forma assíncrona ao repositório, então varrer
  a cada push não adianta a descoberta em um dia e queima cota de uma chave limitada por taxa.
  **O passo de varredura é guardado por `if: env.NVD_API_KEY != ''`** e, quando a chave falta, um
  passo emite `::warning` — pular calado faria "verde por não ter varrido" ficar indistinguível de
  "verde por não ter achado". O secret precisa virar `env` no nível do job porque o contexto
  `secrets` NÃO existe em `if:`, nem de job nem de step. Este job já esteve vermelho por 4
  execuções seguidas (ver Notas de manutenção de 2026-08-17).

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
- `docs/adr/` — decisões com alternativas descartadas. O nome do arquivo é o título, então
  `ls docs/adr/` é o índice — não mantenha cópia da lista aqui. O que os nomes NÃO dizem:
  - **A tabela de moedas do 0004 foi substituída pelo 0009.** Ler o 0004 sozinho faz reimplementar
    BRL dentro do ciclo de missões.
  - **0016–0021 são da verificação de 2026-08-11**: cada um registra a alternativa descartada com o
    motivo MEDIDO — vários deles são a resposta a "por que não fizemos o óbvio?".
  - Os que mudam como se escreve código aqui: 0006 (máquina de estados) · 0007 (geoespacial
    centralizado) · 0008 (ledger e idempotência) · 0009 (economia) · 0010 (uma URI de erro por
    REAÇÃO DE UI) · 0018 (fronteira de `compartilhado`) · 0020 (proximidade é distância MÍNIMA, não
    ao centroide) · 0022 (risco entra na BASE, teto 1,5×) · 0023 (retry POR DENTRO do disjuntor).
- `docs/qualidade/integridade-transacional.md` — evidência de concorrência da carteira (100 threads,
  deadlock, rollback) e a seção "O que esta fase NÃO garante". É o documento a defender oralmente.
- `docs/qualidade/modelo-previsao.md` — métricas do modelo de risco, matriz de confusão, correlações
  injetadas e a discussão falso positivo × falso negativo. **Abre declarando que os dados são
  sintéticos** e fecha com o que a fase não garante. É o outro documento a defender oralmente, e a
  resposta preparada para "sua acurácia é menor que a de um chute?" está nele.
- `docs/qualidade/mutacao.md` — score de mutação (PIT) de `missoes.dominio` e `carteira.dominio`, e
  os SOBREVIVENTES comentados, que são a entrega de verdade: quatro fronteiras de saldo e de teto sem
  teste no valor exato, um equivalente que **não** se conserta (a ordem do lock), e uma superfície
  pública que só o próprio teste usa. Sem gate — `./mvnw -Pmutacao …`, fora do `verify`.
- `docs/evidencias/f21-carga.md` — a medição de carga da **F12b** (o nome diz f21 por pedido; a fase
  é F12b). Três cenários k6, sem afinar parâmetro nenhum. É de onde vem a Pendência #3.
- `docs/seguranca/autenticacao.md` — modelo de ameaça e desenho do fluxo de auth.
- `docs/seguranca/antifraude-geolocalizacao.md` — o que os controles de check-in **não** pegam.
- `docs/evidencias/f6-explain-analyze.md` — saída real do `EXPLAIN ANALYZE` provando uso do índice
  GiST, gerada por `IndiceGeoespacialTest`.
- `docs/evidencias/f12-ciclo-ponta-a-ponta.md` — ciclo completo executado ponta a ponta.
- `docs/INFRA.md` — containers, credenciais de dev, lista completa de usuários seed com tribo.
- `docs/evidencias/` — tem **índice próprio** (`README.md`), com o comando que gerou cada evidência
  e uma seção do que elas NÃO provam. As da F13 medem a execução do zero e a conservação por
  categoria.
- `docs/qualidade/` — evidência de build por data (2026-08-05, 08-06, 08-15 e 08-16; as verificações
  de 08-07 e 08-11 estão nas **Notas de manutenção** do `PROGRESSO.md`, não aqui). Também
  `matriz-rastreabilidade.md`: requisito → endpoint/tela → teste → resultado → evidência, **com os
  não implementados e a justificativa de cada um** (§2.3).
- `docs/diagramas/` — sete diagramas Mermaid, validados por RENDERIZAÇÃO e não por leitura. A
  `arquitetura-alvo.md` é a única que descreve o que NÃO existe, e está marcada como tal.
- `docs/EVOLUCAO-ARQUITETURAL.md` — a linha do tempo das decisões e a história completa do defeito
  econômico: como foi detectado, por que a reconciliação não o pegou, e a distinção entre as
  invariantes de reconciliação e conservação. É o documento a defender oralmente.
- `docs/COMPARATIVO-TECNOLOGIAS.md` · `docs/DIVERGENCIAS-DOCUMENTACAO.md` · `docs/ROTEIRO-DEMO.md` ·
  `CHANGELOG.md` — entrega acadêmica da F13.
- `documentacao/` — o PDF da entrega acadêmica. Não é fonte de verdade técnica: envelhece a cada
  fase e não é atualizado junto com o código.
- `CONTRIBUTING.md` — tabela de tipos de Conventional Commit aceitos e checklist pré-commit.

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
  - `db/migration` — schema, **V1–V8 e V11–V27**; único location do perfil default/prod.
    Próxima é **V28**. **V9 e V10 estão queimadas — nunca as reutilize.** Foram os arquivos de seed
    antes da renomeação para `V900__seed_dev.sql`, então um banco de dev criado antes dela tem as
    versões 9 e 10 gravadas no `flyway_schema_history` com descrição de seed. Um `V9__*.sql` novo em
    `db/migration` passaria em clone novo e falharia em máquina antiga com erro de checksum ou
    "detected applied migration not resolved locally" — divergência que não aparece no CI.
  - `db/seed` — só dev e test (via `application-dev.yml` / `application-test.yml`), faixa **900+**.
    Hoje são sete: `V900__seed_dev.sql`, `V901__seed_entregas_falidas.sql`,
    `V902__seed_alertas_consentimentos.sql`, `V903__seed_cidade_lider.sql` (dados de demonstração
    na zona leste — ver docs/INFRA.md) e `V904__seed_entrega_falida_fixtures.sql` (ponto LOTADO e
    os dois únicos usuários com NOTIFICACAO+LOCALIZACAO vigentes, sem os quais dois caminhos do
    webhook não têm fixture) e `V905__seed_patrocinador.sql` (os patrocinadores de `transportadora-dev`
    e `transportadora-teste`, mais o backfill de `fonte_pote` que a V23 sozinha não alcança — os
    seeds rodam DEPOIS dela. `outra-transportadora` fica sem patrocinador de propósito: é a fixture
    do desfecho SEM_PATROCINIO) e `V906__seed_beneficios.sql` (parceiros e benefícios da Cidade
    Líder, com um parceiro INATIVO e um benefício INATIVO como fixtures de catálogo).
    **Próximo seed é V907.**
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

**Backend fechado até F7 e auditado fase a fase, F8 implementada, mobile F9–F12 implementadas,
F13 (entrega final) concluída em 2026-08-16.** Build verde, 0 falhas, SpotBugs limpo, os dois gates
JaCoCo passando. As duas auditorias do mobile acharam **dois defeitos que uma
revisão comum deixou passar** — a aba de missões gastando o prompt de permissão sem justificativa, e
a conta anonimizada continuando a escrever por 15 minutos. **Os dois estão corrigidos** (o segundo na
verificação de 2026-08-11 — ver Pendências). Detalhe por fase em `docs/PROGRESSO.md`.

**O histórico do git engana na numeração das fases**: o commit "F8 - Fundação Mobile" entregou, na
verdade, F9–F11, e a branch `feat/f13-previsao-risco-entrega` entregou **F12c** — a F13 de verdade
saiu depois, na branch `docs/f13-entrega-final`. **F12b (testes de carga e endurecimento) fechou em
2026-08-25** — ver `docs/evidencias/f21-carga.md`. `docs/PROGRESSO.md` tem a numeração correta — não
infira fase do `git log`.

**Cuidado com o prefixo `f21-` em `docs/evidencias/`: ele cobre DUAS fases diferentes.**
`f21-dependency-check.md` é da F21 (cadeia de dependências); `f21-carga.md` é da **F12b**, e recebeu
esse nome por pedido explícito, contra a convenção `f<fase>-<assunto>.md` do próprio diretório. O
`PROGRESSO.md` é a fonte da fase, não o nome do arquivo.

**F8 — "Fim da Entrega Falida".** O webhook de transportadora, autenticado por HMAC sobre o corpo
bruto (ADR 0021), converte entrega falida em missão de retirada ABERTA no ponto de custódia
(ADR 0020): valida vaga sob `FOR UPDATE`, incrementa ocupação, congela recompensa em XP+TOKEN,
notifica por tribo com consentimento e teto por hora, e dá baixa na custódia quando a missão conclui.
`tools/carrier-mock/enviar.sh` exercita o caminho feliz e os cinco negativos contra o servidor de pé.
**F8 fechou em 2026-08-20 com a carteira de patrocinador** (ADR 0024): a missão de retirada nasce com
o pote já financiado pela transportadora, e a antiga cunhagem por missão virou um aporte ADMIN
auditado e idempotente.

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

**1. A outbox abandona evento em silêncio, e não há carta-morta.** `DrenadorOutboxService` tenta no
máximo `app.outbox.maximo-tentativas` (5) vezes; depois disso o predicado de
`OutboxRepository.buscarPendentesParaPublicar` deixa de enxergar a linha e o evento **nunca mais é
tentado**. Ele fica na tabela, com `publicado_em` nulo e `ultimo_erro` preenchido, e **nada o
mostra**: não existe consulta de esgotados, endpoint de administração nem métrica. O único vestígio
é o `log.warn` da última falha, que ninguém coleta — Prometheus e Grafana foram cortados do MVP.

Consequência: um `MissaoConcluida` que o despachante não consiga tratar cinco vezes desaparece. O
executor recebeu o crédito e nunca é avisado, e não há lugar onde esse fato apareça.

**Isto foi descoberto como comentário falso, não como bug novo** (varredura de 2026-08-20,
`docs/auditoria/varredura-orfaos.md` §1.1). Três lugares afirmavam a garantia que não existe —
"retry até conseguir", "entrega at-least-once" e "espera intervenção". Os três foram corrigidos para
dizer a verdade; **a lacuna em si continua aberta de propósito**, porque fechá-la é decisão de
projeto: uma consulta de esgotados exposta a ADMIN, um contador, ou aceitar a perda explicitamente.
Não decida sozinho — muda o contrato de entrega de notificação.

**2. Nada acha pote imobilizado.** Token preso em missão não-terminal parada (`EM_ANDAMENTO`,
`AGUARDANDO_CONFIRMACAO`, `EM_DISPUTA`) viola a CONSERVAÇÃO enquanto a reconciliação segue
respondendo `integro=true` — são invariantes diferentes, e a primeira passa enquanto a segunda é
violada. **Não existe consulta, endpoint nem relatório que mostre esses potes.**

Existiu a aparência de um: `MissaoRepository.potesImobilizados`, com javadoc dizendo que
"existe para dar visibilidade a essa diferença", e o ADR 0015 registrando essa visibilidade como
consequência aceita. **Nenhum serviço, endpoint ou teste jamais a chamou.** A query foi removida
como órfã em 2026-08-20 e o ADR 0015 recebeu a retificação, em vez de manter código morto que fazia
a lacuna parecer coberta. A mitigação real que EXISTE é outra, e é preventiva, não detectiva: a
varredura por prazo (`ExpiracaoMissoesService`) e a porta de ADMIN (`POST /missoes/{id}/destravar`)
tiram a missão do limbo. O que falta é o instrumento de DIAGNÓSTICO — ver Pendência #1, que é o
mesmo formato de problema.

**3. O alerta de ponto lotado não tem teto nem deduplicação.** Achado no teste de carga de
2026-08-25 (`docs/evidencias/f21-carga.md` §6): uma rajada de webhooks contra um ponto de custódia
cheio gravou **631 linhas idênticas** em `alerta`, para o mesmo ponto, em menos de 3 minutos.

O alerta é **global de propósito** (`usuario_id` nulo — ver `DespachanteAlertaService
.gravarPontoLotado`), então o teto de `app.notificacoes.alertas-por-hora`, que é POR USUÁRIO,
corretamente não se aplica: não é notificação de ninguém, é sinal de operação. A intenção do javadoc
é boa — "um ponto que recusa encomendas com frequência é exatamente o dado que justifica negociar
mais capacidade".

**Mas 631 linhas com a mesma frase não são esse dado — são o apagamento dele**, e são amplificação
de escrita sem limite disparada por evento externo que o sistema não controla: uma transportadora em
laço de retry contra um ponto cheio escreve indefinidamente. Não corrigido de propósito — a medição
foi pedida sem ajuste, e a correção (deduplicar por `(ponto, janela)`, ou contador em vez de linha)
muda o contrato do alerta operacional. **Não decida sozinho.**
