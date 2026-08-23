# Painel de impacto conferido contra o banco

**Data:** 2026-08-23
**Endpoint:** `GET /api/v1/admin/impacto` (ADR 0029)

Um painel não conferido contra o banco é exatamente o tipo de afirmação sem evidência que as
auditorias deste projeto existem para achar. Aqui estão os dois resultados **lado a lado**: o que o
endpoint devolveu e o que uma contagem manual por SQL, escrita de forma diferente, devolveu sobre o
mesmo banco no mesmo momento.

---

## Como o estado foi montado

```bash
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
make reset                                   # destrói o volume e recria; 32 migrations, até V906
cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
bash tools/carrier-mock/enviar.sh            # executado DUAS vezes
```

O `carrier-mock` roda o ciclo inteiro — reporte da falha → aceitar → iniciar → **check-in** →
confirmação da transportadora → crédito do executor — mais os negativos dos dois webhooks. Foi
executado duas vezes: a primeira abortou no meio (o script usa `docker compose` e a máquina roda
podman), então há duas conversões da primeira passada e o ciclo completo da segunda. **O estado é o
que é** — não foi arrumado para o painel ficar bonito.

Saída relevante da segunda execução:

```
── Ciclo completo: falha reportada → vizinho executa → transportadora confirma ──
  OK   reporte da falha → vira missão      HTTP 200
       executor: alice@omnitribo.dev  saldo ANTES: 41 tokens
  ..   aceitar                                ACEITA
  ..   iniciar                                EM_ANDAMENTO
  ..   check-in                               AGUARDANDO_CONFIRMACAO
  OK   confirmação → executor creditado    HTTP 200
       saldo DEPOIS: 107 tokens  (creditados: 66)
```

---

## 1. O endpoint

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@omnitribo.dev","senha":"Senha@123"}' | jq -r .accessToken)

curl -s http://localhost:8080/api/v1/admin/impacto -H "Authorization: Bearer $TOKEN"
```

```json
{
    "geradoEm": "2026-08-23T06:52:33.536179904Z",
    "entregasFalidas": {
        "recebidas": 28,
        "convertidas": 10,
        "pendentes": 16,
        "recusadasPontoLotado": 2,
        "recusadasSemPatrocinio": 0,
        "taxaConversao": 0.3571
    },
    "missoesDeRetirada": {
        "criadas": 4,
        "concluidas": 1,
        "taxaConclusao": 0.25,
        "medianaAteCheckinSegundos": 0,
        "amostraMediana": 1
    },
    "custoEvitado": {
        "reentregasEvitadas": 1,
        "premissaCustoReentregaBrl": 25.00,
        "baseBrl": 25.00,
        "menos50Brl": 12.50,
        "mais50Brl": 37.50
    },
    "tokens": {
        "aportados": 10000,
        "emCarteiras": 10491,
        "emPotes": 354,
        "emCirculacao": 10845,
        "resgatados": 0
    }
}
```

## 2. A contagem manual

```bash
podman exec -i omnitribo-db psql -U omnitribo -d omnitribo   # as quatro consultas abaixo
```

```sql
SELECT COUNT(*)                                                            AS recebidas,
       COUNT(*) FILTER (WHERE missao_id IS NOT NULL)                       AS convertidas,
       COUNT(*) FILTER (WHERE missao_id IS NULL AND motivo_recusa IS NULL) AS pendentes,
       COUNT(*) FILTER (WHERE motivo_recusa = 'PONTO_LOTADO')              AS lotado,
       COUNT(*) FILTER (WHERE motivo_recusa = 'SEM_PATROCINIO')            AS sem_patrocinio,
       ROUND(COUNT(*) FILTER (WHERE missao_id IS NOT NULL)::numeric
             / NULLIF(COUNT(*), 0), 4)                                     AS taxa_conversao
FROM entrega_falida;
```
```
+-----------+-------------+-----------+--------+----------------+----------------+
| recebidas | convertidas | pendentes | lotado | sem_patrocinio | taxa_conversao |
+-----------+-------------+-----------+--------+----------------+----------------+
|        28 |          10 |        16 |      2 |              0 |         0.3571 |
+-----------+-------------+-----------+--------+----------------+----------------+
```

```sql
SELECT COUNT(*)                                       AS criadas,
       COUNT(*) FILTER (WHERE status = 'CONCLUIDA')    AS concluidas,
       ROUND(COUNT(*) FILTER (WHERE status = 'CONCLUIDA')::numeric
             / NULLIF(COUNT(*), 0), 4)                 AS taxa_conclusao
FROM missao WHERE criador_id = '00000000-0000-0000-0000-000000000001';
```
```
+---------+------------+----------------+
| criadas | concluidas | taxa_conclusao |
+---------+------------+----------------+
|       4 |          1 |         0.2500 |
+---------+------------+----------------+
```

```sql
-- A mediana pelo caminho OPOSTO ao do código: aqui o banco faz o join e o percentile_cont;
-- no serviço, duas portas devolvem instantes e o Java ordena. Ver ADR 0029 §3.
SELECT COUNT(*)                                                      AS amostra,
       percentile_cont(0.5) WITHIN GROUP (
         ORDER BY EXTRACT(EPOCH FROM (c.primeiro - ef.recebido_em)))  AS mediana_s
FROM entrega_falida ef
JOIN (SELECT missao_id, MIN(criado_em) AS primeiro
        FROM checkin WHERE valido = TRUE GROUP BY missao_id) c ON c.missao_id = ef.missao_id
WHERE ef.missao_id IS NOT NULL AND c.primeiro >= ef.recebido_em;
```
```
+---------+-----------+
| amostra | mediana_s |
+---------+-----------+
|       1 |  0.464296 |
+---------+-----------+
```

```sql
SELECT (SELECT COALESCE(SUM(valor_tokens),0) FROM lancamento
         WHERE motivo='APORTE_PATROCINADOR' AND sinal='CREDITO')   AS aportados,
       (SELECT COALESCE(SUM(saldo_tokens),0) FROM carteira)        AS em_carteiras,
       (SELECT COALESCE(SUM(pote_tokens),0)  FROM missao)          AS em_potes,
       (SELECT COALESCE(SUM(saldo_tokens),0) FROM carteira)
     + (SELECT COALESCE(SUM(pote_tokens),0)  FROM missao)          AS em_circulacao,
       (SELECT COALESCE(SUM(valor_tokens),0) FROM lancamento
         WHERE motivo='RESGATE' AND sinal='DEBITO')                AS resgatados;
```
```
+-----------+--------------+----------+---------------+------------+
| aportados | em_carteiras | em_potes | em_circulacao | resgatados |
+-----------+--------------+----------+---------------+------------+
|     10000 |        10491 |      354 |         10845 |          0 |
+-----------+--------------+----------+---------------+------------+
```

## 3. Confronto

| Métrica | Endpoint | SQL manual | |
|---|---:|---:|---|
| entregas falidas recebidas | 28 | 28 | ✅ |
| convertidas | 10 | 10 | ✅ |
| pendentes (na custódia, sem missão) | 16 | 16 | ✅ |
| recusadas por lotação | 2 | 2 | ✅ |
| recusadas por patrocínio | 0 | 0 | ✅ |
| taxa de conversão | 0,3571 | 0,3571 | ✅ |
| missões de retirada criadas | 4 | 4 | ✅ |
| concluídas (= re-entregas evitadas) | 1 | 1 | ✅ |
| taxa de conclusão | 0,25 | 0,2500 | ✅ |
| amostra da mediana | 1 | 1 | ✅ |
| mediana webhook → check-in | 0 s | 0,464296 s | ✅ ¹ |
| tokens aportados | 10.000 | 10.000 | ✅ |
| em carteiras | 10.491 | 10.491 | ✅ |
| em potes | 354 | 354 | ✅ |
| em circulação | 10.845 | 10.845 | ✅ |
| resgatados | 0 | 0 | ✅ |

¹ **A única diferença, e ela é de unidade, não de valor.** O endpoint expõe SEGUNDOS INTEIROS
(`Duration.toSeconds()` trunca); o `percentile_cont` devolve fração. `ImpactoTest` cobre isso com
tolerância de 1 s, e não arredondando os dois lados — arredondar esconderia o caso em que a
implementação estivesse de fato errada. O valor sub-segundo é real: o `carrier-mock` faz o check-in
no mesmo instante do webhook, o que não representa nenhum bairro e é por isso que `amostraMediana`
está no painel.

**Somatório dos desfechos:** `10 + 16 + 2 + 0 = 28` ✅ — a identidade que `ImpactoTest.funilBateComOBanco`
trava. Foi ela que descobriu o quarto desfecho: na primeira execução somava 6 contra 22.

---

## 4. A premissa vem de configuração, não de código

Mesmo banco, mesmos dados, servidor reiniciado com a premissa sobrescrita:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-Dapp.impacto.custo-reentrega-brl=42.90"
```

```json
"custoEvitado":{"reentregasEvitadas":1,"premissaCustoReentregaBrl":42.90,
                "baseBrl":42.90,"menos50Brl":21.45,"mais50Brl":64.35}
```

Contra os `25.00 / 12.50 / 37.50` da execução anterior. **Nenhum número de custo está fixo em
código** — e a faixa acompanha a premissa, que é o que a torna uma análise de sensibilidade e não
uma decoração.

---

## 5. Autorização

```bash
curl -s -o /dev/null -w "HTTP %{http_code}\n" .../admin/impacto            # HTTP 401
curl -s .../admin/impacto -H "Authorization: Bearer $ALICE"                # HTTP 403
{"detail":"Acesso negado","instance":"/api/v1/admin/impacto","status":403,
 "title":"Forbidden","type":"https://omnitribo.dev/problemas/acesso-negado", ...}
```

---

## 6. Suíte

```
services/api $ ./mvnw verify
[INFO] Tests run: 703, Failures: 0, Errors: 0, Skipped: 2
[INFO] All coverage checks have been met.      (global 80%)
[INFO] All coverage checks have been met.      (dominio 85%)
[INFO] BugInstance size is 0
[INFO] BUILD SUCCESS

apps/mobile $ npm run typecheck && npm run lint && npm test
tsc --noEmit                     (sem erros)
eslint                           ✖ 9 problems (0 errors, 9 warnings)   ← todos pré-existentes
Test Suites: 14 passed, 14 total
Tests:       188 passed, 188 total
```

---

## O que isto NÃO prova

- **Não prova impacto real.** Os dados são de seed e de um script de demonstração local. A mediana
  de tempo de resposta mede o `carrier-mock`, não um bairro; a amostra é de **uma** missão.
- **Não prova que houve economia.** Prova que 1 encomenda foi retirada por um vizinho. Que isso
  tenha evitado uma re-entrega é interpretação (ADR 0029 §4), e quanto vale é premissa (§5).
- **Não valida a premissa de R$ 25,00.** Ela continua sem medição — a evidência acima mostra que ela
  é configurável e que o resultado a acompanha, o que é outra coisa.
- **Não prova desempenho.** Nenhuma medição de tempo de resposta do endpoint foi feita, e a consulta
  varre três tabelas inteiras. Em base grande isso importa; aqui são 28 linhas de `entrega_falida`.
  A medição é a **F12b**, que segue pendente.
- **Não prova o snapshot consistente.** O `REPEATABLE_READ` do `ImpactoService` está justificado no
  ADR 0029 §2 e não há teste de concorrência que o exercite — nada aqui mostra o painel sendo lido
  durante uma escrita. É a lacuna conhecida desta entrega.
- **Não confere a tela.** O confronto acima é do endpoint. A tela é coberta por
  `app/__tests__/impacto.test.tsx`, contra MSW — não contra este banco.
