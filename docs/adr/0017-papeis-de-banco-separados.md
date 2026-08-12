# 0017 — Papéis de banco separados: aplicação sem DDL, Flyway sem runtime

**Data:** 2026-08-11
**Status:** Aceito

---

## Contexto

As migrations criam o papel `omnitribo_app` com `SELECT, INSERT` apenas nas quatro tabelas
append-only (`lancamento`, `auditoria`, `checkin`, `missao_evento`) e revogam `UPDATE, DELETE`. O
comentário SQL descrevia isso como "defesa em profundidade… mesmo que o código da aplicação tente
executá-los".

**Mas a aplicação não conectava com esse papel.** `application-dev.yml`, `application-test.yml` e
`application.yml` (de onde produção herda) resolviam `${DATASOURCE_USERNAME}` para `omnitribo` — o
DONO das tabelas, para quem GRANT e REVOKE simplesmente não se aplicam. A proteção existia no catálogo
e estava desligada em runtime.

E nada acusava: `MigracaoTest` lia `information_schema.role_table_grants` — confirmava que o papel
estava correto, nunca que a aplicação o usava. Um teste que roda como dono jamais esbarra no
privilégio que deveria proteger o ledger.

Pendência #1 do CLAUDE.md, aberta havia três fases.

---

## Decisão

**Duas conexões, dois papéis.**

- **Datasource da aplicação:** `omnitribo_app`. Sem `UPDATE`/`DELETE` nas tabelas append-only, sem DDL.
- **Flyway:** credencial própria (`spring.flyway.user`), com DDL — o papel de aplicação não pode criar
  schema, e dar DDL a ele anularia a proteção inteira.

Em produção, ambas sem default: falta de configuração derruba o boot em vez de cair silenciosamente
num usuário mais poderoso do que deveria.

**Nos testes, o `JdbcTemplate` injetado passou a ser de OPERADOR** (`OperadorBancoTestConfig`,
`@Primary`), conectando como dono. Os testes fazem `DELETE FROM` nas tabelas append-only em **33
lugares** para limpar estado entre casos, e todos falhariam com `permission denied`.

A saída não foi afrouxar o papel da aplicação — isso jogaria fora a proteção. Foi reconhecer que **o
teste não é a aplicação**: quando ele limpa uma tabela append-only, está no papel de operador do
banco, que em produção é uma pessoa com acesso administrativo, não o serviço. Zero dos 33 sites
precisou de edição.

**Duas provas novas em `MigracaoTest`:**

1. `aplicacao_nao_consegue_apagar_nem_alterar_o_ledger_em_runtime` — usa o datasource da APLICAÇÃO e
   tenta `DELETE`/`UPDATE` de verdade, esperando SQLState **42501**. Compara SQLState e não texto: a
   mensagem do PostgreSQL é traduzida conforme o locale do servidor.
2. `toda_tabela_do_schema_tem_ao_menos_SELECT_para_a_aplicacao` — **obrigatório**, não zelo:
   `ddl-auto: validate` valida por `DatabaseMetaData.getTables()`, que no driver PostgreSQL lê
   `pg_catalog` (world-readable). Uma tabela nova sem `GRANT` **passa no validate**, o contexto sobe,
   e o erro só aparece no primeiro `SELECT` em runtime, como 500.

Nenhuma migration nova foi necessária: as 15 tabelas já tinham `GRANT` (V2–V7), `GRANT USAGE ON
SCHEMA` estava na V8, e não há `SEQUENCE` (todos os PKs são UUID).

---

## Consequências

**Positivas:**
- A imutabilidade do ledger passa a ser garantia do BANCO, não só disciplina do código — que é o que
  o ADR 0008 argumentava sozinho.
- Um `UPDATE` acidental em código de produção falha alto, em vez de corromper a trilha em silêncio.
- O teste de cobertura de GRANT fecha uma classe inteira de erro que o `validate` não vê.

**Negativas / trade-offs:**
- Duas credenciais para configurar em produção, e um modo de falha novo: `FATAL: role "omnitribo_app"
  does not exist` se a ordem Flyway → aplicação não valer. É determinístico e barulhento — derruba
  toda a suíte de uma vez, não um caso raro.
- Um datasource a mais nos testes (pool minúsculo), que conta contra o `max_connections` do contêiner.
- Um desenvolvedor que rode SQL à mão como `omnitribo_app` vai esbarrar no privilégio. É o
  comportamento desejado.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Manter o dono no runtime e confiar na disciplina do código | É o estado que gerou a pendência. O `REVOKE` era decorativo, e três fases se passaram sem ninguém notar. |
| Reescrever os 33 sites de `DELETE` para um helper com datasource próprio | Muito mais invasivo pelo mesmo resultado. Trocar de onde vem o `JdbcTemplate` injetado resolve com um arquivo. |
| `ALTER DEFAULT PRIVILEGES` para o papel de aplicação | Daria `UPDATE`/`DELETE` automático a tabelas append-only FUTURAS — o inverso exato do objetivo. |
| Datasource restrito só em teste | Deixaria produção sem a proteção, justamente onde ela importa. |
| Dar DDL ao `omnitribo_app` para simplificar | Se ele pode `ALTER TABLE`, pode remover o próprio `REVOKE`. A separação inteira perde sentido. |
