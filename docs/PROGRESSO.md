# Progresso — Omni-Tribo

| Fase | Nome                        | Status         | PR  | Data       |
|------|-----------------------------|----------------|-----|------------|
| F0   | Fundação Monorepo           | ✅ Concluído    | —   | 2026-08-04 |
| F1   | Infraestrutura Local        | ✅ Concluído    | —   | 2026-08-04 |
| F2   | Identidade e Autenticação   | ✅ Concluído    | —   | 2026-08-05 |
| F3   | Cadastro de Missões         | ✅ Concluído    | —   | 2026-08-06 |
| F4   | Aceite e Ciclo de Vida      | ✅ Concluído    | —   | 2026-08-06 |
| F5   | Carteira e Economia         | ✅ Concluído    | —   | 2026-08-07 |
| F6   | Geolocalização              | ⬜ Pendente     | —   | —          |
| F7   | Logística (carrier-mock)    | ⬜ Pendente     | —   | —          |
| F8   | Notificações                | ⬜ Pendente     | —   | —          |
| F9   | App Mobile — Autenticação   | ⬜ Pendente     | —   | —          |
| F10  | App Mobile — Missões        | ⬜ Pendente     | —   | —          |
| F11  | App Mobile — Carteira       | ⬜ Pendente     | —   | —          |
| F12  | Testes de Carga e Segurança | ⬜ Pendente     | —   | —          |
| F13  | Entrega Final               | ⬜ Pendente     | —   | —          |

## Notas de manutenção

- **2026-08-07** — **F5 entregue** (branch `feat/f5-carteira-economia`): módulo `carteira` completo,
  com as três camadas. Build verde com **266 testes**, 0 falhas/erros (+79), em duas execuções
  consecutivas com números idênticos nos testes de concorrência. Decisões em
  [`adr/0008-ledger-append-only-e-idempotencia.md`](adr/0008-ledger-append-only-e-idempotencia.md);
  evidência de concorrência em
  [`qualidade/integridade-transacional.md`](qualidade/integridade-transacional.md).
  - **`lancamento` é a verdade, `carteira.saldo_*` é projeção**, ambas na mesma transação. Um único
    ponto do sistema escreve saldo: `LivroRazaoService`.
  - **Idempotência fechada pelo LOCK, não pela sondagem.** Todo caminho trava a linha serializadora
    antes de sondar a chave, então a corrida entre sondar e inserir não existe.
    `uk_lancamento_idempotencia` é barreira final que nunca deve disparar — nada captura
    `DataIntegrityViolationException`. Chave gravada é `sha256(operacao|ator|…)`, nunca a do cliente.
  - **Ordem global de lock `missao` → `carteira` (id crescente) → `usuario`**, que torna o deadlock
    A→B / B→A impossível por construção. Provado em 100 rodadas de transferências cruzadas
    simultâneas: 200 respostas 201, zero 500.
  - **Conclusão via `/confirmar` existente**, sem endpoint novo: a transição `CONFIRMAR → CONCLUIDA`
    já estava declarada desde a F4 e o stub 501 virou o corpo real. `/resolver` com `RESOLVER_CONCLUIR`
    reusa o mesmo serviço de crédito.
  - **Conservação da moeda**: missão TRIBO/COLETA paga o executor do `missao.pote_tokens` financiado,
    sem cunhar token. Publicar exige pote cobrindo a recompensa; cancelar ou expirar estorna aos
    financiadores — sem o estorno, os tokens ficariam presos e a conservação seria falsa.
  - **Outbox transacional** com SKIP LOCKED e backoff exponencial. Entrega at-least-once sem broker.
  - **Lacuna fechada de tabela**: o registro de usuário NÃO criava carteira, então todo usuário novo
    ficaria sem onde receber crédito e a primeira conclusão falharia com 404.
    `ProvisionamentoCarteira.garantirCarteira` entrou em `AutenticacaoService.registrar`, na mesma
    transação.
  - **Migrations em V13/V14, não V12**: a branch `feat/f6-geolocalizacao` já ocupa a V12 com
    `V12__checkin_idempotencia.sql`, e versão de Flyway é sequência global — colidir daria "more than
    one migration with version 12" no merge das duas fases.
  - Armadilha registrada durante a execução: a guarda de executor nulo estava ANTES da validação de
    transição e transformava um 409 legítimo em 422, quebrando o contrato 403 → 409 que o
    `MissaoControllerTest` já verificava desde a F4. Ordem corrigida para autorizar → sondar replay →
    validar transição → regra de negócio.
  - **Achados dos revisores, todos corrigidos com teste de regressão.** `revisor-seguranca`:
    o job de expiração não passa por `MissaoService.aplicar`, então o estorno do pote era **código
    morto para EXPIRADA** — e a perda seria invisível para a reconciliação, porque token sumindo do
    POTE não quebra a igualdade ledger/carteira; financiar acima da recompensa prendia a sobra;
    `ConstraintViolationException` do header `Idempotency-Key` virava 500 com `log.error` (nos
    controllers com `@Validated`, o Spring desliga a validação embutida do MVC e a violação vem do
    proxy AOP). `revisor-testes`: `assertLedgerReconcilia` fechava sete testes sem nunca ter sido
    visto acusando — agora `ReconciliacaoTest` prova que a consulta consegue falhar, e o endpoint
    admin ganhou teste de 200/403/401; saque ganhou o teste de concorrência que o CLAUDE.md exige;
    uma asserção do teste de rollback verificava o próprio mock e foi removida.
  - **`max_connections=300` no Testcontainer.** O limite de conexões é por SERVIDOR e a suíte mantém
    vários contextos Spring vivos (o de `@MockitoBean` é próprio), cada um com o pool inteiro —
    consumo é nº de contextos × pool. Subir o pool para acomodar as 100 threads sem isso derrubava
    outra classe com `FATAL: sorry, too many clients already`, que aparece como "Failed to load
    ApplicationContext" e não aponta para a causa.

- **2026-08-06** — Auditoria do `CLAUDE.md` contra o código, e correção das armadilhas que ela
  revelou. Build verde com **187 testes**, 0 falhas/erros — nenhum teste novo: a leva é de correção
  estrutural, não de comportamento.
  - **Seed fora da faixa de schema.** `V9__seed_dev.sql` e `V10__senha_prefixo_bcrypt.sql` viraram
    um único **`V900__seed_dev.sql`**. Versão de Flyway é sequência global, não por pasta: com o
    seed em V9/V10 no meio da faixa, um `V9__*.sql` novo em `db/migration` derrubaria dev e test
    com *"more than one migration with version 9"*, sem que o erro apontasse para `db/seed`. A
    faixa 900+ garante por construção que o seed é o último. Como consequência ele passou a rodar
    **depois** da V11 e precisou gravar dados em forma final: `'ABERTA'` no lugar de `'DISPONIVEL'`
    e `{bcrypt}` embutido no hash (era isso que o V10 fazia). Efeito colateral assumido: os
    `UPDATE` de renomeação da V11 não afetam mais nenhuma linha — ver ADR 0006, Notas de manutenção.
  - **Divergência Jackson entre main e test eliminada.** `MockMvcTestConfig` declarava um bean de
    `ObjectMapper` do Jackson 2 — justificado por uma hipótese não verificada ("JacksonAutoConfiguration
    pode não ser ativado") — enquanto a aplicação serializa com Jackson 3. A suíte afirmava sobre
    JSON parseado por uma major diferente da que o produz. O bean foi removido e os testes passaram
    a usar `TesteIntegracaoMvcBase.JSON`, um `JsonMapper` construído sem injeção, no mesmo padrão do
    `MissaoService.MAPPER_TRILHA`. Que o bean era dispensável já estava à vista: `TesteIntegracaoBase`
    nunca o importou e sempre fez roundtrip HTTP com JSON sem problema.
  - **`make up` sem `.env`** falhava com erro de Docker que não apontava a causa. Alvo de arquivo
    `.env` no Makefile, pré-requisito de todos os targets que leem o compose.
  - **Skill `/verificar`** quebrava no passo 2 porque `apps/mobile/` não tem `package.json` (F9+).
    Agora reporta NÃO VERIFICADO em vez de falhar.
  - Armadilha descoberta durante a execução, registrada no CLAUDE.md: **renomear migration exige
    `./mvnw clean`**. O Maven não remove de `target/classes` o arquivo com o nome antigo, o Flyway
    encontra os dois e aplica os dois — o sintoma é `duplicate key value violates unique constraint`,
    sem relação aparente com a renomeação.

- **2026-08-06** — Correção do CI e fechamento das lacunas de segurança da F4. O workflow `api.yml`
  nunca gerava as chaves RSA (`services/api/keys/` é gitignored), então o `@PostConstruct` do
  `JwtService` derrubava o contexto Spring e **todas** as classes de teste de integração falhavam no
  GitHub, embora passassem localmente. O javadoc do `JwtTestConfig` afirmava que `@Primary` protegia
  disso — não protege: `@Primary` só desempata injeção, o bean real continua sendo instanciado.
  Fechado também: `@Auditavel` nas 8 escritas de missão (a anotação existia mas não era usada em
  método nenhum, o aspecto era advice que nunca disparava), `entidade_id` na trilha via
  `RecursoAuditavel`, rate limit em `POST /auth/registrar` (era amplificador de DoS — cada chamada
  custa um hash Argon2id sem nenhum limite), e o `CorrelationIdFilter` registrado no `MockMvc`, que
  não herda filtros de servlet fora da cadeia do Security. Build verde com **187 testes**, 0
  falhas/erros (+11). Novos: `CabecalhosSegurancaTest`, `BloqueioProgressivoTest`,
  `AuditoriaMissaoTest`, `RegistroRateLimitTest`.
- **2026-08-06** — F3+F4 entregues juntas (branch `feat/f4-ciclo-vida-missoes`): máquina de estados
  de missão com 9 estados e 12 transições declaradas no próprio `StatusMissao`, trilha append-only
  gravada na mesma transação, autorização anti-IDOR com ator sempre vindo do JWT, e aceite
  concorrente serializado por lock pessimista. Decisões em
  [`adr/0006-maquina-estados-missao.md`](adr/0006-maquina-estados-missao.md). Build verde com
  **176 testes**, 0 falhas/erros — 118 deles cobrindo a matriz completa de status × evento.
  Três endpoints publicam contrato e respondem 501 até suas fases: `checkin` (F6), `confirmar` e
  `resolver` (F7). Evidência: [`qualidade/verificacao-2026-08-06.md`](qualidade/verificacao-2026-08-06.md).
- **2026-08-05** — Verificação completa pós-F2: build verde (19 testes, 0 falhas/erros). Dois
  *warnings* de build corrigidos — exclusão de `UserDetailsServiceAutoConfiguration` (senha-dev morta
  a cada boot) e extração do `@TestConfiguration` aninhado para top-level (forward-compat Spring
  Framework 7.1). Relatório com evidência: [`qualidade/verificacao-2026-08-05.md`](qualidade/verificacao-2026-08-05.md).
