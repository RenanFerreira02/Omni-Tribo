# Verificação — 2026-08-05 (pós-F2)

Registro da verificação completa de tudo que foi implementado até a **F2 (Identidade e
Autenticação)**, com a evidência real de cada checagem e as correções aplicadas. Segue o protocolo
do `/verificar`. Nada aqui foi presumido: cada linha corresponde à saída de um comando executado.

## Resultado

| Área         | Comando                                   | Resultado |
|--------------|-------------------------------------------|-----------|
| Backend      | `./mvnw verify`                           | ✅ BUILD SUCCESS — 19 testes, 0 falhas / 0 erros / 0 skips (~21 s) |
| Formatação   | Spotless (Google Java Format)             | ✅ 78 arquivos limpos, 0 precisam de mudança |
| Análise estática | SpotBugs                              | ✅ análise concluída sem bloqueio |
| Migrations   | Flyway em PostGIS real (`MigracaoTest`)   | ✅ 10 migrations validadas, schema `public` na versão 10 |
| Banco (dev)  | `docker compose ps`                       | ✅ `omnitribo-db` (postgis/postgis:16-3.5) up, `healthy`, porta 5432 |
| Segredos     | `git ls-files` / `git check-ignore` / grep no tree | ✅ `keys/*.pem` e `.env` não versionados e cobertos pelo `.gitignore`; só `.env.example` no tree; nenhum PEM privado versionado |
| Mobile       | `npm run typecheck && lint && test`       | ⚪ Não aplicável — `apps/mobile` ainda não scaffoldado (fases F9–F11) |

### Testes por classe (surefire)

| Classe                        | Testes | Tempo   | Cobre |
|-------------------------------|:------:|---------|-------|
| `AuthControllerTest`          | 11     | ~2,2 s  | login válido/ inválido (mensagem genérica), `/me`, token expirado, rotação de refresh, reuso → revogação de família, rate limit 429, senha comum, ausência de senha/token em log |
| `RefreshTokenFamiliaTest`     | 1      | ~0,1 s  | concorrência: 10 threads no mesmo refresh → só 1 sucede, família revogada |
| `MigracaoTest`                | 4      | ~8,9 s  | Flyway aplica todas as migrations em PostGIS real |
| `PingControllerTest`          | 2      | ~0,9 s  | health check |
| `RegrasArquiteturaTest`       | 1      | ~0,8 s  | ArchUnit: fronteiras de módulo (só via `api/` pública ou evento) |
| **Total**                     | **19** |         | |

## Correções aplicadas

O build já passava, mas emitia dois *warnings* legítimos. Ambos foram corrigidos (o resto do código
está verde, testado e já revisado — não houve refatoração além do necessário).

### 1. Exclusão de `UserDetailsServiceAutoConfiguration`

- **Sintoma:** a cada boot (3× durante os testes) o Spring imprimia `Using generated security
  password: …` e configurava um `InMemoryUserDetailsManager`.
- **Causa:** sem exclusão explícita, o Spring Boot auto-configura um usuário em memória quando não há
  `UserDetailsService`. Esta API é **stateless por JWT** e não usa esse mecanismo.
- **Correção:** `@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)` em
  `ApiApplication` (FQN em Spring Boot 4.1: `org.springframework.boot.security.autoconfigure`).
- **Por que é seguro:** varredura confirmou **zero** uso de `AuthenticationManager` /
  `UserDetailsService` / `AuthenticationProvider` em `src/main`. A autenticação casa a senha
  diretamente via `PasswordEncoder` (Argon2id, `SenhaConfig`) em `AutenticacaoService`. O usuário em
  memória era código morto que só poluía o log e sugeria um mecanismo de auth inexistente.

### 2. Extração do `@TestConfiguration` aninhado (forward-compat Spring Framework 7.1)

- **Sintoma:** `… 'default' context configuration classes were detected but are currently ignored:
  TesteIntegracaoMvcBase$MockMvcAutoConfig. In Spring Framework 7.1, these classes will no longer be
  ignored.`
- **Causa:** `MockMvcAutoConfig` era uma `@TestConfiguration` **estática aninhada** na classe-base de
  teste. O Spring a detecta como "default configuration class"; hoje ignora, mas a partir do 7.1
  passaria a incluí-la no contexto — mudando silenciosamente o wiring dos testes.
- **Correção:** extraída para a classe **top-level** `MockMvcTestConfig`, ativada apenas por
  `@Import` em `TesteIntegracaoMvcBase`. Wiring explícito e imune à mudança de versão. Os beans
  (`MockMvc`, `ObjectMapper`) continuam vindo pelo mesmo `@Import`; as subclasses não mudaram.

## Estado após as correções

- Build **verde**, mesmas 19 contagens de teste, e **os dois warnings ausentes** do log
  (confirmado por grep no log do `verify`).
- *Warnings* benignos remanescentes, deliberadamente sem ação:
  - `extension "postgis" already exists, skipping` — idempotência esperada (init script + `V1`).
  - Aviso de *byte-buddy dynamic agent* — instrumentação do Mockito na JVM.
  - Probe inicial do Testcontainers em `docker.sock` que se recupera sozinho.

## O que NÃO foi verificado (e por quê)

- **Mobile** (`apps/mobile`): só contém `.gitkeep` + `CLAUDE.md`. Sem `package.json`/código — não há
  o que testar. É trabalho das fases F9–F11.
- **Carga / latência sob concorrência real:** fora de escopo até a **F12 (Testes de Carga e
  Segurança)**. Nesta fase não há sinal de problema de performance: o tempo do `verify` é dominado
  pela subida do contêiner PostGIS (Testcontainers), e o custo do Argon2id é **intencional**
  (memory-hard), não *lag*.
