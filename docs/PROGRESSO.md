# Progresso — Omni-Tribo

| Fase | Nome                        | Status         | PR  | Data       |
|------|-----------------------------|----------------|-----|------------|
| F0   | Fundação Monorepo           | ✅ Concluído    | —   | 2026-08-04 |
| F1   | Infraestrutura Local        | ✅ Concluído    | —   | 2026-08-04 |
| F2   | Identidade e Autenticação   | ✅ Concluído    | —   | 2026-08-05 |
| F3   | Cadastro de Missões         | ✅ Concluído    | —   | 2026-08-06 |
| F4   | Aceite e Ciclo de Vida      | ✅ Concluído    | —   | 2026-08-06 |
| F5   | Carteira e Economia         | ⬜ Pendente     | —   | —          |
| F6   | Geolocalização              | ✅ Concluído    | —   | 2026-08-07 |
| F7   | Logística (carrier-mock)    | ⬜ Pendente     | —   | —          |
| F8   | Notificações                | ⬜ Pendente     | —   | —          |
| F9   | App Mobile — Autenticação   | ⬜ Pendente     | —   | —          |
| F10  | App Mobile — Missões        | ⬜ Pendente     | —   | —          |
| F11  | App Mobile — Carteira       | ⬜ Pendente     | —   | —          |
| F12  | Testes de Carga e Segurança | ⬜ Pendente     | —   | —          |
| F13  | Entrega Final               | ⬜ Pendente     | —   | —          |

## Notas de manutenção

- **2026-08-07** — **F6 — Geolocalização.** Build verde com **248 testes**, 0 falhas/erros (eram 187).
  Radar de proximidade, check-in geolocalizado com validação 100% servidor, cache Caffeine e trilha
  antifraude append-only.
  - **`GET /api/v1/missoes/proximas`** — `ST_DWithin` + `ST_Distance`, raio default 2000 m e máximo
    20000 m, ordenado por distância crescente, distância em metros no DTO. **Uso do índice GiST
    provado**: `Index Scan` em `idx_missao_origem` sobre 200 mil linhas, com `ANALYZE` e sem tocar
    em `enable_seqscan`. Saída real em `docs/evidencias/f6-explain-analyze.md`.
  - **Uma única classe com PostGIS**: `compartilhado/infra/ConsultasGeoespaciais`. A regra "um repo
    geo por módulo" do ADR 0002 não sobreviveu à segunda consulta — as duas consultas da fase estão
    em módulos diferentes e a regra ArchUnit é direcional, então `ST_*` acabaria em dois arquivos.
    Ver **ADR 0007**. Os stubs `CheckinGeoRepository` e `PontoCustodiaGeoRepository` foram apagados.
  - **Cache Caffeine** com chave por geohash de precisão 7 (~153 m) + raio + categoria + limite, TTL
    30 s, invalidado **depois do commit** (`TransactionSynchronization.afterCommit`) — invalidar
    dentro da transação deixaria uma leitura concorrente repopular com estado pré-commit e a entrada
    obsoleta sobreviveria o TTL inteiro. **Cinco** pontos de invalidação, não dois: `criar`,
    `atualizar` (PATCH move `origem` de missão ABERTA sem mudar status), `aplicar`, `registrarCheckin`
    e `expirarLote` — este último chama a máquina de estados direto, sem passar por `aplicar`.
  - **Check-in**: `V12` acrescenta `chave_idempotencia` (UNIQUE) e `suspeito` a `checkin`. A chave
    guardada é `sha256(usuario|missao|chave_do_cliente)`, não a chave crua — a UNIQUE é global, e a
    chave crua deixaria o cliente que manda `"1"` receber replay do check-in alheio.
  - **A rejeição é gravada E o 422 é devolvido.** A primeira versão fez isso com `REQUIRES_NEW`, e
    estava errada: a transação externa segura `FOR UPDATE` sobre a missão enquanto a interna pede uma
    SEGUNDA conexão, então bastava concorrência ≥ tamanho do pool para travar a aplicação inteira —
    inclusive o login — com 30 s de timeout e 500 para todos. Só apareceu quando
    `CheckinConcorrenteTest` foi escrito, com 50 threads. **A correção não foi aumentar o pool**: o
    serviço passou a devolver a recusa como VALOR (`ResultadoRegistroCheckin`), a transação commita
    nos dois casos e o controller lança o 422 depois do commit. Uma transação, uma conexão, e de
    quebra o caminho aceito ficou atômico — antes havia uma janela em que a linha de check-in existia
    sem a transição correspondente.
  - **Dois bugs achados pelos próprios testes de integração, corrigidos no código e não na
    asserção:** (1) `Duration.toSeconds()` truncava, e dois check-ins a menos de 1 s davam velocidade
    nula — o teleporte **mais** implausível era o único não sinalizado; passou a milissegundos.
    (2) o cálculo em milissegundos expôs estouro de `velocidade_implicita_kmh` (`NUMERIC(10,2)`) num
    deslocamento intercontinental entre duas requisições HTTP: derrubava o check-in com 500 em vez de
    marcá-lo. Satura no máximo da coluna, porque o número é sinal, não medida.
  - **Ordem de checagens do check-in**: 403 → sondagem de idempotência → 409 → gravação. A sondagem
    fica entre o 403 e o 409 porque um replay legítimo chega com a missão já em
    `AGUARDANDO_CONFIRMACAO` e levaria 409; e depois do 403 porque antes dele um não-executor
    receberia dados da missão. Exigiu tornar `MissaoStateMachine.validarAutorizacao` pública.
  - `docs/seguranca/antifraude-geolocalizacao.md` registra o que os controles **não** pegam:
    spoofing com root/emulador é mitigável e não eliminável, `mocked` é reportado pelo cliente,
    presença não é execução, conluio não é detectado, e a cinemática é cega no primeiro check-in de
    cada conta.

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
  - **`make up` sem `.env`** falhava (exit 1, `env file ... not found`). Alvo de arquivo `.env` no
    Makefile, pré-requisito de todos os targets. Medido no Compose v5.3.1: só `up` e `config`
    quebram — `down`, `logs` e `ps` operam sobre containers já rotulados e não precisam resolver a
    definição do serviço. A guarda elimina o passo manual num clone novo; não existe para decifrar
    mensagem de erro, que é explícita.
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
