# Progresso — Omni-Tribo

| Fase | Nome                        | Status         | PR  | Data       |
|------|-----------------------------|----------------|-----|------------|
| F0   | Fundação Monorepo           | ✅ Concluído    | —   | 2026-08-04 |
| F1   | Infraestrutura Local        | ✅ Concluído    | —   | 2026-08-04 |
| F2   | Identidade e Autenticação   | ✅ Concluído    | —   | 2026-08-05 |
| F3   | Cadastro de Missões         | ✅ Concluído    | —   | 2026-08-06 |
| F4   | Aceite e Ciclo de Vida      | ✅ Concluído    | —   | 2026-08-06 |
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
