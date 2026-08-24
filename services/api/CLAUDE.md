# Backend (`services/api/`)

Este arquivo entra em contexto ao trabalhar em `services/api/`. O `CLAUDE.md` da raiz continua
valendo: produto, arquitetura modular, economia das três moedas e as **Regras não negociáveis**
(banco, segurança, testes, git) estão lá e não se repetem aqui.

Antes de terminar qualquer tarefa: `./mvnw verify`, e cole a saída real. Compilar não é testar.

## Convenções e armadilhas

- DTOs são `record`. Entidade JPA nunca cruza fronteira do controller.
- Exceções de domínio herdam de `DominioException` → mapeadas para status HTTP no handler global → resposta RFC 9457 `ProblemDetail`.
- Identidade do usuário logado no controller: injete `@AuthenticationPrincipal AutenticadoPrincipal principal` — nunca extraia do corpo ou query string.
- Teste de integração: use `TesteIntegracaoBase` (RANDOM_PORT + TestRestTemplate) para roundtrip HTTP real; use `TesteIntegracaoMvcBase` (WebEnvironment.MOCK + MockMvc) quando precisar inspecionar headers de resposta. Ambas estendem `ContainerConfig` (PostgreSQL+PostGIS singleton). Spring Boot 4.1 removeu `@AutoConfigureMockMvc` — não tente usá-lo.
- **MockMvc não herda filtro de servlet auto-registrado por `@Component`** — monta só a cadeia do
  Spring Security. Por isso o `CorrelationIdFilter` é adicionado à mão em `MockMvcTestConfig`, e por
  isso o `RateLimitFilter` funciona sem gambiarra (entra pela cadeia, via `addFilterBefore` no
  `SecurityConfig`). Filtro novo exige decidir por qual dos dois caminhos ele entra; esquecer disso
  não quebra teste nenhum, só faz a suíte exercitar uma cadeia diferente da que roda em produção.
- **Toda função PostGIS vive numa ÚNICA classe: `compartilhado/infra/ConsultasGeoespaciais`.** Nenhum
  `ST_*` existe fora dela. Substitui a regra "um `*GeoRepository` por módulo" do ADR 0002, que não
  sobreviveu à segunda consulta — as duas consultas geoespaciais estão em módulos diferentes e a
  regra ArchUnit é direcional, então `ST_*` acabaria em dois arquivos. Ver **ADR 0007**; os stubs
  `CheckinGeoRepository` e `PontoCustodiaGeoRepository` foram apagados. Usa `JdbcClient`, não
  `@Query(nativeQuery=true)`, que exigiria interface ligada a uma `@Entity` — e a única entidade
  visível de `compartilhado` seria `Outbox`. Parâmetros nomeados e zero concatenação continuam
  obrigatórios. Query nativa NÃO geoespacial continua em `infra/` do próprio módulo.
- `CAST(:param AS ...)` nas queries nativas não é decoração: um parâmetro nulo sem tipo chega ao
  PostgreSQL como `bytea` e a consulta estoura com "function ... does not exist". Ver
  `MissaoRepository.buscarComFiltros` e `ConsultasGeoespaciais`.
- Escrita de domínio auditável tem DUAS metades, e faltar uma não quebra nada em tempo de compilação:
  o método de serviço leva `@Auditavel(acao=..., entidade=...)`, e o DTO de resposta que ele devolve
  implementa `RecursoAuditavel.idAuditoria()`. Sem a anotação o `AuditoriaAspecto` é advice que nunca
  dispara; sem a interface ele grava `entidade_id` nulo e a trilha vira "alguém publicou uma missão"
  sem dizer QUAL — inútil para reconstruir incidente. Padrão a copiar: os métodos anotados em
  `MissaoService` + `MissaoResponse implements RecursoAuditavel`. Eventos de autenticação são a
  exceção deliberada: gravados à mão no `AutenticacaoService`, porque precisam de `atorId` nulo.
- `fail-on-unknown-properties: false` (em `application.yml`) é decisão de segurança, não default
  frouxo: o DTO de request declara só o que pode mudar, então mandar `status`, `executorId` ou
  `xpRecompensa` num PATCH é silenciosamente ignorado. É a proteção contra mass assignment — não
  "endureça" isso para 400 achando que melhora.
- Spotless (Google Java Format) é verificado no `verify`. Se falhar por formatação, rode `./mvnw spotless:apply`.
- Jackson é **3** (`tools.jackson`) em todo o repositório, main e test. Spring Boot 4.1 autoconfigura
  esse mapper e não existe bean de `com.fasterxml.jackson.databind.ObjectMapper` — injetá-lo impede
  o contexto de subir. Quem precisa serializar constrói o próprio `JsonMapper`, sem injeção: veja
  `MissaoService.MAPPER_TRILHA` no main e `TesteIntegracaoMvcBase.JSON` nos testes. Em teste novo,
  use `JSON` — não declare bean de mapper.
- AOP: `spring-boot-starter-aop` não existe no Boot 4.x. O suporte a `@Aspect` vem de
  `aspectjweaver` declarado direto no `pom.xml`.
- Mudança de status de missão passa SEMPRE por `MissaoStateMachine`. Nunca chame `missao.setStatus(...)` fora dela.
- Mudança de SALDO passa SEMPRE por `LivroRazaoService`. Nunca chame `carteira.creditar/debitar(...)`
  fora dele: o ledger e a projeção têm de ser escritos na mesma transação, ou divergem em silêncio.
- **Toda operação de valor segue esta ordem, sem exceção: adquira todos os locks → sonde a chave de
  idempotência → valide as regras → escreva.** É o lock que fecha a corrida entre sondar e inserir,
  não a sondagem. Ordem global dos locks: `missao` → `carteira` (id CRESCENTE) → `usuario`; travar
  duas carteiras fora de ordem crescente reabre o deadlock A→B / B→A.
- Nada captura `DataIntegrityViolationException` para tratar replay. O no-op é decidido pela sondagem
  sob lock; se `uk_lancamento_idempotencia` disparar é defeito, e sobe como 500. Capturar dentro da
  transação já a marcou rollback-only, então o "tratamento" produziria um commit impossível.
- **Armadilha da primeira leitura, em três entidades.** Se `Carteira` (ou `Missao`, ou `Usuario`) já
  estiver no persistence context, o Hibernate devolve a instância em cache SEM reemitir o
  `SELECT ... FOR UPDATE` — o teste passa e o lock nunca existiu. Por isso resolver
  `usuarioId → carteiraId` usa a projeção escalar `buscarIdPorUsuario`, nunca `findByUsuarioId`, e
  por isso `buscarParaAtualizar(missaoId)` é sempre a PRIMEIRA leitura da transação (inclusive antes
  da checagem de rascunho alheio, em `registrarCheckin`).
- **Todo corpo de erro sai com `type` do catálogo `TipoProblema`, nunca `about:blank`.** O `type` é
  o que o app usa para decidir comportamento; status HTTP sozinho é ambíguo (dois 409 diferentes
  pedem reações diferentes) e `detail` é texto para humano, que muda a cada revisão de copy. Há
  TRÊS caminhos que produzem erro e os três precisam concordar: o `GlobalExceptionHandler`, os ~15
  handlers herdados do `ResponseEntityExceptionHandler` (cobertos pelo override de
  `createResponseEntity`) e os escritores manuais de JSON em `SecurityConfig` e `RateLimitFilter`,
  que rodam na cadeia de filtros, antes do DispatcherServlet. Esquecer o terceiro é o erro fácil:
  401 e 429 são justamente os que o app mais recebe.
- **Quem escreve JSON à mão num filtro precisa de `setCharacterEncoding(UTF_8)` explícito.**
  `setContentType("application/problem+json")` não define charset, o servlet cai em ISO-8859-1 e
  "Autenticação necessária" chega ao cliente como Latin-1 rotulado de JSON — que é UTF-8 por
  definição (RFC 8259 §8.1). Não aparece em teste que só olha status code.
- Erro de regra de negócio no servidor é `RegraNegocioVioladaException` → **422**, não 409. O 409 diz
  "não cabe neste estado, caberia em outro"; o 422 diz "cabe no estado, mas os dados não satisfazem".
  A ordem de checagem é 403 → 409 → 422: inverter 409 e 422 quebra o contrato que o app já integra.
  **O check-in insere a sondagem de idempotência no meio: 403 → sondagem → 409 → gravação.** Fica
  depois do 403 porque antes dele um não-executor receberia dados da missão; e antes do 409 porque um
  replay legítimo chega com a missão já em `AGUARDANDO_CONFIRMACAO` e levaria 409. É por isso que
  `MissaoStateMachine.validarAutorizacao` é pública.
- **Recusa de regra que precisa ser GRAVADA volta como VALOR, não como exceção.** Lançar de dentro da
  transação apagaria a linha no rollback; usar `REQUIRES_NEW` para preservá-la trava a aplicação
  inteira (ver Arquitetura). O serviço devolve um resultado (`ResultadoRegistroCheckin`,
  `ResultadoCheckin`), a transação commita nos dois casos, e o **controller** lança o 422 depois do
  commit. Padrão a copiar em qualquer trilha antifraude/auditoria futura.
- Evento de domínio vai para a outbox por `PublicadorEventos`, na mesma transação do fato. Nunca
  notifique direto: antes do commit você anuncia o que o rollback desfaz; depois, perde o que falhar.
  O `DrenadorOutboxJob` drena com `SKIP LOCKED` e backoff exponencial (30s, 1min, 2min, 4min, 8min;
  `maximo-tentativas: 5`), e o `DespachanteAlerta` grava uma linha em `alerta` — destino provisório
  até o push real do mobile. O consumidor tem de tolerar duplicata, porque a entrega não é
  exactly-once. **Mas NÃO diga que é at-least-once**: na quinta falha o evento sai do predicado do
  lote e nunca mais é tentado, sem carta-morta, sem endpoint e sem métrica — zero entregas, e
  ninguém fica sabendo. Ver Pendência #1 do `CLAUDE.md` da raiz.
- Cache de proximidade (`CacheMissoesProximas`, Caffeine, TTL 30s, chave por geohash de precisão 7 +
  raio + categoria + limite) é invalidado **depois do commit**, via
  `TransactionSynchronization.afterCommit` — invalidar dentro da transação deixaria uma leitura
  concorrente repopular com estado pré-commit, e a entrada obsoleta sobreviveria o TTL inteiro. Por
  isso NÃO se usa `spring-boot-starter-cache`/`@CacheEvict`, que dispara dentro da transação. São
  **cinco** pontos de invalidação, não dois: `criar`, `atualizar`, `aplicar`, `registrarCheckin` e
  `expirarUma` — este último chama a máquina de estados direto, sem passar por `aplicar`, e agora roda
  uma transação POR MISSÃO (o laço vive em `ExpiracaoMissoesJob.varrer`, fora do bean, senão o
  `@Transactional` seria ignorado por auto-invocação).
- Ao escrever teste, saiba o que `application-test.yml` desliga de propósito — três coisas, todas
  para não mascarar o que o teste mede:
  - rate limit de leitura/escrita em 10000/min: um teste de rate limit precisa sobrescrever o valor;
  - `app.agendamento.habilitado: false`: o job de expiração não roda, para não mudar status entre
    arrange e assert. A regra é testada chamando `ExpiracaoMissoesJob.varrer()` direto;
  - pool Hikari em **40**, dimensionado pelo teste mais pesado (`ConclusaoConcorrenteTest`, 100
    threads). Não é 100 porque as threads serializam atrás de um único `FOR UPDATE` na missão; 40 é
    margem para runner de CI lento não virar `SQLTransientConnectionException` — que apareceria como
    500 e seria lido como bug de concorrência em vez do problema de infra que é.
- Para autenticar em teste sem passar pelo `/auth/login`, use `JwtTestConfig.gerarTokenValido(...)`
  e `gerarTokenExpirado(...)`. Login real esbarra no bloqueio de 5 tentativas/min, e um teste com
  muitos usuários falharia por 429 em vez de pela regra em avaliação. Fixtures: `MissaoFixture` e
  `SuporteCarteira`.
- O `@Primary` do `JwtTestConfig` **não** impede o `JwtService` real de ser instanciado — `@Primary`
  só desempata injeção quando há mais de um candidato. O `@PostConstruct` do bean real roda de
  qualquer jeito e lê os PEM do disco, e é por isso que as chaves são obrigatórias mesmo para rodar
  só testes, e que `api.yml` tem um passo `gerar-chaves-dev.sh` antes do `verify`. Remover esse
  passo do CI derruba TODA a suíte de integração no GitHub enquanto tudo continua verde local.

## Economia — o que nunca vem do cliente (ADR 0009)

- Recompensa é CALCULADA pelo servidor em `CalculadoraDeRecompensa` (`missoes/dominio`, função pura)
  e congelada na criação junto com `versao_formula`. O DTO de criação não tem `xpRecompensa` nem
  `tokensRecompensa`. A conclusão LÊ o congelado — nunca recalcula.
- Mudou parâmetro em `app.missoes.recompensa`? **SUBA `versao` junto.** O teste dourado
  (`CalculadoraDeRecompensaTest.douradoV1`) falha de propósito para forçar essa decisão.
- Nenhuma missão remunera em BRL: `ck_missao_economia` (V15) exige `valor_brl = 0` em toda categoria.
- Complexidade é derivada de peso e volume quando existem (ENTREGA e COLETA os exigem); declarada
  quando não existem (TRIBO, AJUDA). Declarar junto com peso e volume é 400.
- **Duas invariantes DIFERENTES, e confundi-las já custou caro:** reconciliação
  (ledger == projeção) e conservação (`SUM(carteiras) + SUM(potes)`). **A primeira passa enquanto a
  segunda muda** — foi esse o buraco do estorno na expiração, foi a cunhagem de ENTREGA, e agora é a
  queima do resgate, que é intencional. Um endpoint de reconciliação respondendo `integro=true` não
  é prova de que nenhum token se perdeu.
- **A conservação é de CICLO, não de estoque** (ADR 0027). `SUM(carteiras) + SUM(potes)` é constante
  dentro do ciclo de missões e muda nas duas pontas: sobe no `APORTE_PATROCINADOR`, desce no
  `RESGATE`. Um teste que afirme constância precisa dizer QUAL das duas coisas mede.
- **Quem paga do pote é `missao.fonte_pote`, não a categoria** (V23 / ADR 0024). `COMUNIDADE` e
  `PATROCINADOR` pagam do pote; `CUNHAGEM` emite na conclusão e, desde o ADR 0025, é só ENTREGA
  criada por humano. A coluna é congelada no construtor de `Missao` — não há CHECK de coerência no
  banco porque ele reprovaria os INSERTs dos seeds, que rodam depois da migration.
- **Regra que depende da fonte se lê da FONTE, nunca de uma lista de categorias.** `validarEstado`
  do financiamento listava TRIBO/COLETA e por isso quase ficou fora de sincronia com o construtor
  quando AJUDA mudou de lado: a missão exigiria pote para publicar e recusaria todo financiamento
  que o formasse — impublicável e infinanciável ao mesmo tempo, sem erro apontando a causa.
- **Motivo de financiamento novo entra em `LancamentoRepository.buscarFinanciamentosDaMissao` no
  mesmo commit em que entra no enum.** Aquela query é o que o estorno enxerga; um motivo fora dela
  deixa o token preso numa missão morta, e a reconciliação continua verde.
