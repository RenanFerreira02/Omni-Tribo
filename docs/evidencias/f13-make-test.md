# `make test` — saída real

**Data:** 2026-08-16 · **Fase:** F13 · **Comando:** `make test` (alvo implementado nesta fase)

Roda `./mvnw verify` no backend e `npm test` no mobile, em sequência. O `verify` não é só
teste: inclui Spotless, SpotBugs em `failOnError` e dois gates de cobertura JaCoCo.

```console
$ make test
==> Backend: ./mvnw verify
[INFO] Tests run: 637, Failures: 0, Errors: 0, Skipped: 2
[INFO] --- spotless:3.9.0:check (spotless-check) @ api ---
[INFO] Spotless.Java is keeping 328 files clean - 0 needs changes to be clean
[INFO] --- spotbugs:4.10.3.0:check (spotbugs-check) @ api ---
[INFO] --- jacoco:0.8.15:check (check-global) @ api ---
[INFO] --- jacoco:0.8.15:check (check-dominio) @ api ---
[INFO] BUILD SUCCESS
[INFO] Total time:  01:03 min

==> Mobile: npm test
Test Suites: 14 passed, 14 total
Tests:       179 passed, 179 total
Snapshots:   0 total
Time:        4.721 s, estimated 5 s
Ran all test suites.
```

## Números

| | Testes | Falhas | Erros | Pulados |
|---|---|---|---|---|
| Backend (JUnit 5 · Testcontainers · ArchUnit) | **637** | 0 | 0 | 2 |
| Mobile (Jest · RTL · MSW), 14 suítes | **179** | 0 | 0 | 0 |

Os 2 pulados são deliberados e não são falha mascarada — nenhum `@Disabled` foi acrescentado
nesta fase.

## O que NÃO entra no `make test`

- **`npm run test:e2e`**, de propósito: exige o backend em execução. Roda com
  `E2E_API_URL=http://localhost:8080 npm run test:e2e`.
- **A varredura de dependências** (OWASP Dependency-Check), que vive no profile `seguranca` e
  exige chave da NVD: `./mvnw -Pseguranca verify -Dnvd.api.key=$NVD_API_KEY`.
