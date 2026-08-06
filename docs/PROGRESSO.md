# Progresso — Omni-Tribo

| Fase | Nome                        | Status         | PR  | Data       |
|------|-----------------------------|----------------|-----|------------|
| F0   | Fundação Monorepo           | ✅ Concluído    | —   | 2026-08-04 |
| F1   | Infraestrutura Local        | ✅ Concluído    | —   | 2026-08-04 |
| F2   | Identidade e Autenticação   | ✅ Concluído    | —   | 2026-08-05 |
| F3   | Cadastro de Missões         | ⬜ Pendente     | —   | —          |
| F4   | Aceite e Ciclo de Vida      | ⬜ Pendente     | —   | —          |
| F5   | Carteira e Economia         | ⬜ Pendente     | —   | —          |
| F6   | Geolocalização              | ⬜ Pendente     | —   | —          |
| F7   | Logística (carrier-mock)    | ⬜ Pendente     | —   | —          |
| F8   | Notificações                | ⬜ Pendente     | —   | —          |
| F9   | App Mobile — Autenticação   | ⬜ Pendente     | —   | —          |
| F10  | App Mobile — Missões        | ⬜ Pendente     | —   | —          |
| F11  | App Mobile — Carteira       | ⬜ Pendente     | —   | —          |
| F12  | Testes de Carga e Segurança | ⬜ Pendente     | —   | —          |
| F13  | Entrega Final               | ⬜ Pendente     | —   | —          |

## Notas de manutenção

- **2026-08-05** — Verificação completa pós-F2: build verde (19 testes, 0 falhas/erros). Dois
  *warnings* de build corrigidos — exclusão de `UserDetailsServiceAutoConfiguration` (senha-dev morta
  a cada boot) e extração do `@TestConfiguration` aninhado para top-level (forward-compat Spring
  Framework 7.1). Relatório com evidência: [`qualidade/verificacao-2026-08-05.md`](qualidade/verificacao-2026-08-05.md).
