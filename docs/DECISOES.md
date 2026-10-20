# Decisões

Uma entrada por decisão, com a numeração original (`ADR 0024` nos comentários do código aponta
para cá). Formato: **o que decidimos** · *por quê* · alternativa descartada.

Decisão nova entra no fim, com o próximo número.

---

### 0001 — Monólito modular
Um deploy, oito módulos (`compartilhado · identidade · missoes · geolocalizacao · carteira ·
logistica · notificacoes · integracoes`), cada um com `api/ dominio/ infra/`. Módulo só fala com
outro por porta em `api/` ou por evento; o **ArchUnit reprova** o resto. *Um time e uma transação
não justificam coordenação distribuída; as fronteiras já ficam prontas para extrair.*
Descartado: microsserviços.

### 0002 — PostgreSQL + PostGIS
Schema só por Flyway (`ddl-auto: validate`). Proximidade, raio de check-in e tribos por distância
são resolvidos no banco com índice GiST. Descartado: Firestore — consulta geoespacial filtrada em
memória.

### 0003 — React Native / Expo
Expo SDK 57, TypeScript strict, Expo Router, TanStack Query e Zustand. *Uma base para Android/iOS e
demonstração sem build nativo, pelo Expo Go.* Descartado: Flutter, que era o protótipo jogado fora.

### 0004 — Três moedas
XP (reputação), TOKEN (moeda do bairro) e BRL. **A linha do BRL foi revogada pela 0009.**

### 0005 — JWT RS256 + Argon2id + refresh com rotação de família
Access de 15 min, assinado em RSA. Refresh opaco de 256 bits, guardado como `sha256`, que rotaciona
a cada uso; o reuso de um refresh antigo revoga a família inteira. Senha em Argon2id (OWASP).
Descartado: HS256 (verificar exigiria o segredo de assinar) e bcrypt.

### 0006 — Máquina de estados explícita
`StatusMissao` declara a tabela de transições, e `MissaoStateMachine` é o único caminho para mudar
de status. O aceite usa lock pessimista (`FOR UPDATE`): dois candidatos simultâneos, um vence. Hoje
são 9 estados e 20 transições (0015, 0034).

### 0007 — Consultas geoespaciais numa classe única
Todo `ST_*` vive em `compartilhado`, atrás da porta `ConsultasGeoespaciais`. Ela não conhece tipo de
módulo nenhum: recebe `String` e devolve `AlvoProximo(id, distância)`. *A regra do ArchUnit é
direcional, e "um repositório geo por módulo" espalharia `ST_*`.*

### 0008 — Ledger append-only, idempotência sob lock, outbox
`lancamento` é a verdade e `carteira.saldo_*` é projeção, escritos na mesma transação; correção é
estorno, nunca UPDATE. A ordem é sempre **locks → sondar a chave de idempotência → validar →
escrever**. Eventos saem por outbox transacional (teto de 5 tentativas; **não** é at-least-once —
ver 0031). Descartado: broker de mensageria.

### 0009 — Economia do cuidado: TOKEN recompensa, BRL fora
Nenhuma missão paga em BRL (`ck_missao_economia`). A recompensa é XP + TOKEN, **calculada pelo
servidor e congelada na criação**; o DTO não aceita valor. **Quem cria não paga.** Token não é
conversível em real: token conversível *é* dinheiro, com KYC junto.

### 0010 — Uma URI de erro por reação de UI
Erro é RFC 9457 `ProblemDetail`, e o app decide pelo `type`, nunca pelo `detail`. Uma causa ganha
URI própria só quando a tela faz algo diferente por ela (ex.: as três recusas de check-in).

### 0011 — Integrações externas e anonimização
Clima (Open-Meteo) e CEP (ViaCEP) entram pelo módulo `integracoes`, escolhidos por **não exigirem
chave**. Se o provedor falha, a resposta é 503 com `type` estável e a UI esconde o recurso. A
exclusão de conta LGPD **anonimiza** em vez de apagar, porque o ledger é append-only.

### 0012 — Mapa por WebView + Leaflet
Leaflet com tiles OSM numa WebView, atrás de `MapaLeaflet` com props estáveis — nenhuma tela sabe o
motor. *`react-native-maps` exige development build e chave do Google; o projeto roda no Expo Go.*

### 0013 — Segredo por plataforma
No nativo o refresh fica em `expo-secure-store`, e o acesso passa só por
`src/lib/armazenamentoSeguro.ts`. Na web nada é persistido: o refresh de 30 dias não pode ir em
claro para o `localStorage`, então recarregar desloga.

### 0014 — Expiração: uma transação por missão
O job itera e chama `expirarUma` pelo proxy, para que o `@Transactional` valha. Uma transação para o
lote inteiro travaria carteiras fora de ordem e derrubaria tudo por uma falha.

### 0015 — Estado sem saída ganha prazo e porta de ADMIN
`EM_ANDAMENTO` sem check-in vence → `EXPIRADA` com estorno. `AGUARDANDO_CONFIRMACAO` sem resposta
do criador → `CONCLUIDA` **pagando o executor**, porque o check-in é a evidência aceita.
`POST /missoes/{id}/destravar` (ADMIN) cancela e estorna.

### 0016 — Autorização reconferida por requisição
O `JwtAuthFilter` consulta `ConsultaSessao` (cache Caffeine de 60 s, `expireAfterWrite`) e monta o
principal do BANCO. *Conta anonimizada ou rebaixada perde o acesso em até 60 s, não em 15 min.*

### 0017 — Papéis de banco separados
A aplicação conecta como `omnitribo_app`, sem DDL e sem UPDATE/DELETE no ledger. O Flyway tem
credencial própria. `MigracaoTest` prova em runtime (SQLState 42501).

### 0018 — Fronteira de `compartilhado`
`dominio` é kernel e `api` é porta, ambos livres; `infra` é privado, com regra ArchUnit própria. Por
isso `ConsultasGeoespaciais`, `EmissorDeToken` e `ControleDeTentativasLogin` são interfaces em
`compartilhado/api`.

### 0019 — Cabeçalho de cliente não é confiável
O IP vem só de `EnderecoDoCliente` (`getRemoteAddr`); quem resolve proxy é a `RemoteIpValve`.
*Ler `X-Forwarded-For` dava chave nova a cada tentativa e anulava o bloqueio de login.*
`X-Correlation-Id` passa por allowlist (fecha a forja de linha de log).

### 0020 — Ponto de custódia comercial; proximidade por tribo
A entrega falida vai para um ponto com capacidade conhecida (vaga travada com `FOR UPDATE`). O alerta
vai para tribos cuja **menor** distância até o ponto cabe no raio (3 km), e não a distância até o
centroide da tribo.

### 0021 — Webhook de transportadora por HMAC
`HMAC-SHA256(segredo, timestamp + "." + corpo_bruto)`, com janela de 5 min, comparação em tempo
constante e um segredo por transportadora. É idempotente por `(transportadora, codigoRastreio)`, e
os três desfechos respondem 200: `CONVERTIDA`, `RECUSADA` e `SEM_PATROCINIO`. *4xx faria a
transportadora reenviar em laço.*

### 0022 — Previsão de risco: regressão logística em Java puro
Treinada dentro do `./mvnw verify` sobre dataset **sintético**; os coeficientes ficam publicados em
`application.yml`. O multiplicador de risco entra na **base** da recompensa, limitado a
**[1,00; 1,50]**, e é congelado na missão. Detalhe em [MODELO-RISCO.md](MODELO-RISCO.md).

### 0023 — Resiliência: cache → disjuntor → bulkhead → retry → HTTP
Disjuntor próprio com `Clock` injetado e `RetryTemplate` nativo do Spring 7 (resilience4j não tinha
artefato para Boot 4). O retry fica **dentro** do disjuntor, para que uma rajada conte como uma
falha; o `{"erro":true}` do ViaCEP fica **fora**, porque CEP digitado errado não é provedor fora do
ar.

### 0024 — Carteira de patrocinador
A transportadora é um titular de carteira. A emissão de token virou **um evento explícito**,
`APORTE_PATROCINADOR` (ADMIN, auditado, idempotente), e a missão de retirada nasce com o pote já
debitado do patrocinador. `fonte_pote` é congelada por missão.

### 0025 — AJUDA paga do pote como TRIBO
O argumento "vizinhos custeando a logística do varejista" descreve a entrega da transportadora, não
um favor entre vizinhos. AJUDA passou a exigir pote financiado por outros membros.

### 0026 — A transportadora confirma o recebimento
Webhook `/confirmacao` (mesmo HMAC) aplica `CONFIRMAR` e credita o executor. *Check-in prova
presença, não recebimento.* A varredura por prazo continua como rede de segurança.

### 0027 — Resgate queima token
`POST /resgates` debita com motivo `RESGATE` e não credita ninguém. A economia é um ciclo, não um
estoque: o aporte emite, o pote move, a carteira move e o resgate queima. Benefício é `BEM` ou
`PERCENTUAL`, nunca reais.

### 0028 — Busca de vizinho por handle exato
`GET /usuarios/busca?handle=` acha o vizinho só na mesma tribo, e inexistente, de outra tribo e
inativo respondem o mesmo 404. Teto de 12/min. *Uma listagem de membros seria um mapa social do
bairro e uma lista de alvos.*

### 0029 — Painel de impacto
O funil da entrega falida, o tempo até o check-in e a circulação de token são agregados **na hora**,
sem tabela nem cache. O custo de reentrega é **premissa declarada**, mostrada com ±50%. Taxa com
denominador zero é `null`, não 0%.

### 0030 — Radar com alternativa em lista
`Mapa | Lista` na mesma rota: a lista é a rota acessível e o fallback offline. A ordem por distância
vem do servidor.

### 0031 — Carta-morta da outbox
`GET /admin/outbox/esgotados` lista os eventos que passaram de 5 falhas, com `ultimo_erro`, e
`POST /{id}/reenfileirar` os devolve ao drenador. A idempotência é por estado da linha, sem
`Idempotency-Key`.

### 0032 — Diagnóstico de pote imobilizado
`GET /admin/missoes/potes-imobilizados` e o campo `potesImobilizados` na reconciliação, **separado
de `integro`**: são invariantes diferentes. O marco é `janela_fim` para ABERTA e `estado_desde` para
os demais.

### 0033 — Deduplicação do alerta operacional
Uma linha por `(tipo, referência, janela)` em vez de uma por evento (o teste de carga gerou 631
iguais). A frequência é lida em `GET /admin/pontos-custodia/recusas`, agregada de `entrega_falida`.

### 0034 — Destravar os três estados restantes
`DESTRAVAR` passou a valer também em `RASCUNHO`, `ACEITA` e `EM_DISPUTA` (17 → 20 transições).
Todo estado não-terminal tem saída que não depende de um humano específico.

### 0035 — Missão só-XP (`SEM_TOKEN`)
TRIBO e AJUDA podem recompensar só em XP: publicam sem pote e não movem token, ou seja, não são uma
ponta da invariante. A idempotência da conclusão sem lançamento é por estado.

### 0036 — A recompensa acompanha os dados
`PATCH` recalcula a recompensa em RASCUNHO e ABERTA quando um insumo vem no corpo. O que a
publicação fecha é o **pote**, não o valor: abaixo do pote a sobra ficaria presa (422); acima, numa
missão publicada, a conclusão falharia (422).

### 0037 — "Quem cria não paga" vira regra imposta
`FinanciamentoService.validarAutorizacao` recusa `financiador == criador` com 422. *A premissa
estava em quatro ADRs e nenhuma linha a impunha.*
