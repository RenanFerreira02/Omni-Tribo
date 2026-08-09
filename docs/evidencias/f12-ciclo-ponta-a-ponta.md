# Ciclo ponta a ponta — evidência executada

**Data:** 2026-08-09
**Ambiente:** PostgreSQL+PostGIS em Docker (`make up`), Spring Boot no perfil `dev`
(`localhost:8080`), app em `jest.e2e.config.js` (`testEnvironment: 'node'`, sem MSW).

Este documento registra a execução real do ciclo completo do produto. Nada aqui é reconstruído de
memória: cada bloco é saída colada de comando.

---

## 1. O ciclo, contra o backend em execução

`apps/mobile/src/api/__tests__/ciclo.e2e.test.ts`, doze passos com **dois usuários reais** —
alice (Tribo Pinheiros) como criadora, bob (Tribo Vila Madalena) como executor.

```
E2E_API_URL=http://localhost:8080 npm run test:e2e -- --verbose
```

```
  ✓ login real devolve o par de tokens com TTL de 15 minutos (1 ms)
  ✓ GET /auth/me responde com o usuário do JWT (4 ms)
  ✓ GET /missoes devolve o envelope de paginação do backend (10 ms)
  ✓ GET /missoes/proximas devolve distância em metros, medida pelo PostGIS (12 ms)
  ✓ GET /missoes/{id} devolve a missão que a lista anunciou (14 ms)
  ✓ GET /carteira devolve saldo em tokens, com BRL zerado (12 ms)
  ✓ credencial errada chega ao app como naoAutenticado, com type do catálogo (67 ms)
  ✓ 1. a prévia anuncia a recompensa que o servidor vai congelar (5 ms)
  ✓ 2. alice cria a missão, que nasce em RASCUNHO com a recompensa congelada (10 ms)
  ✓ 3. publicar leva a ABERTA e a missão aparece no radar geoespacial (19 ms)
  ✓ 4. bob aceita e inicia; o saldo dele NÃO se move (44 ms)
  ✓ 5. check-in LONGE é recusado, com os números que a tela usa para orientar (21 ms)
  ✓ 6. check-in NO LOCAL transiciona para AGUARDANDO_CONFIRMACAO — ainda sem crédito (17 ms)
  ✓ 7. o mesmo check-in repetido é REPLAY, sem gravar nada novo (8 ms)
  ✓ 8. alice confirma: CONCLUIDA é o ÚNICO estado que credita (23 ms)
  ✓ 9. o crédito aparece no extrato, com motivo legível e saldo após (8 ms)
  ✓ 10. bob transfere tokens para carol, da mesma tribo (27 ms)
  ✓ 11. repetir a transferência com a MESMA chave é replay, não um segundo débito (20 ms)
  ✓ 12. transferir para OUTRA tribo é recusado com 422 (6 ms)

Test Suites: 2 passed, 2 total
Tests:       19 passed, 19 total
```

### O que cada passo prova, e que nenhum teste com mock provaria

| Passo | Afirmação verificada |
|---|---|
| 1 → 2 | A recompensa da prévia é **exatamente** a congelada na criação. O app não reimplementa a fórmula, e o servidor não muda de ideia entre as duas chamadas. |
| 3 | A missão publicada aparece no radar do PostGIS a **menos de 5 m** da coordenada de origem — distância medida por `ST_Distance` sobre `geography`, nunca pelo cliente. |
| 4 | **Aceitar não credita.** O saldo de bob é lido antes e depois: idêntico. É a regra que o protótipo Flutter descartado violava. |
| 5 | Check-in a ~6,3 km é recusado com `checkin-fora-do-raio` e os campos `distanciaM`/`raioM`. E a missão **não** transiciona — a recusa não consome a tentativa legítima. |
| 6 | Check-in no local leva a `AGUARDANDO_CONFIRMACAO`, **ainda sem crédito**: presença não é conclusão. |
| 7 | Mesma chave de idempotência ⇒ replay, sem linha nova e sem 409. |
| 8 | **`CONCLUIDA` é o único estado que credita.** O saldo de bob passa a ser `anterior + tokensDaMissao`, e `saldoBrl` permanece 0. |
| 9 | O crédito aparece no extrato com `motivo = RECOMPENSA_MISSAO`, `sinal = CREDITO` e `saldoAposTokens` coerente. |
| 10–11 | Transferência para a mesma tribo debita uma vez; repetir com a mesma chave devolve `replay: true` e **não debita de novo**. Sem isso, um retry de rede custaria tokens ao usuário. |
| 12 | Transferência para outra tribo é recusada com 422 — token é moeda **comunitária**. |

### Uma armadilha da própria suíte, registrada

Rodar `test:e2e` **duas vezes dentro do mesmo minuto** falha no `beforeAll` com:

```json
{ "tipo": "limiteRequisicoes", "status": 429,
  "detail": "Muitas tentativas. Aguarde 60 segundos.", "retryAfter": 60 }
```

Não é defeito: é o bloqueio de **5 tentativas de login por minuto** da F4. Os dois arquivos de e2e
somam quatro logins por execução — um a menos que o teto. Foi por isso que o teste do ciclo passou a
usar os **UUIDs do seed** para carol e alice em vez de um `me()` após login: cada login economizado é
uma execução a mais antes do bloqueio.

---

## 2. Integridade da economia, medida depois do ciclo

Reconciliação (ledger *versus* projeção de saldo), como ADMIN:

```
GET /api/v1/admin/carteiras/reconciliacao
{
    "carteirasVerificadas": 6,
    "integro": true,
    "divergencias": []
}
```

Conservação do TOKEN, medida direto no banco:

```
docker exec omnitribo-db psql -U omnitribo -d omnitribo -c "..."

 carteiras=548
 potes=0
 total=548
```

> **Leitura honesta destes dois números.** A reconciliação prova que ledger e projeção batem. A
> conservação `SUM(carteiras) + SUM(potes)` vale hoje para TRIBO e COLETA; ENTREGA e AJUDA ainda
> CUNHAM token (Pendência #2 do `CLAUDE.md`), e a missão deste ciclo é AJUDA — logo o total subiu
> pelo valor da recompensa. Isso é conhecido, documentado e fecha na F8, quando a carteira de
> patrocinador financiar o pote. As duas invariantes são DIFERENTES, e a primeira passa enquanto a
> segunda é violada.

---

## 3. Nenhuma tela exibe valor em reais

Requisito explícito. Verificado por três caminhos independentes.

**a) Nenhum literal `R$` em texto renderizado.** As quatro ocorrências no código são todas em
COMENTÁRIO, explicando por que o valor não é exibido:

```
app/missao/[id].tsx:165          "R$ 0,00" sugeriria que um dia haverá outro número ali. Ver ADR 0009. */}
app/(tabs)/carteira.tsx:115      "R$ 0,00" sugeriria que um dia haverá outro número — exatamente a expectativa que o
src/components/MissaoCard.tsx:23 Exibir um "R$ 0,00" seria pior que não exibir nada, porque sugeriria que um
src/components/SaldoToken.tsx:16 Por isso: **ícone próprio e número puro**. Nada de "R$", nada de `Intl.NumberFormat` com
```

**b) Nenhuma formatação monetária.** `grep` por `style: 'currency'`, `NumberFormat.*BRL` e
`toLocaleString.*currency` em `src/` e `app/` devolve apenas a linha de comentário de
`SaldoToken.tsx` que PROÍBE o uso.

**c) `saldoBrl` não é lido por nenhuma tela.** A única ocorrência em `app/` e `src/components/` é o
comentário-âncora de `carteira.tsx:113` explicando a ausência.

**d) Travado por teste**, em quatro arquivos e seis asserções:

```
app/__tests__/missoes.test.tsx:        queryByText(/R\$/)).toBeNull();
app/__tests__/criarMissao.test.tsx:    queryByText(/R\$/)).toBeNull();
app/__tests__/detalheMissao.test.tsx:  queryByText(/R\$/)).toBeNull();
app/__tests__/telas.test.tsx:          queryByText(/R\$/)).toBeNull();   (× 3: onboarding, carteira, perfil)
```

O teste de criação vai além do visual e trava o **payload**: `valorBrl` é `0` e o corpo **não tem**
`xpRecompensa` nem `tokensRecompensa`. Se alguém acrescentar um campo de recompensa ao formulário, o
teste quebra.

---

## 4. O que esta evidência NÃO garante

Na mesma disciplina de `docs/qualidade/integridade-transacional.md`:

- **Renderização do mapa.** O `MapaLeaflet` é mockado nos testes — eles provam que a tela passa os
  marcadores certos e reage às mensagens, não que o Leaflet os desenhou. Ver ADR 0012; verificação
  de mapa continua sendo manual.
- **Clima e CEP reais no ciclo.** O e2e não os exercita: são provedores externos, e um teste que
  depende deles seria vermelho intermitente por causa da internet. Foram verificados por `curl`
  contra os provedores de verdade na entrega do backend, e por `MockRestServiceServer` na suíte.
- **Concorrência do ciclo.** Este teste é sequencial. A prova de concorrência da carteira (100
  threads, deadlock, rollback) está em `docs/qualidade/integridade-transacional.md`.
- **Aparelho real.** Tudo roda em `testEnvironment: 'node'` com stub de `react-native`. GPS,
  permissões e gestos continuam sendo verificação manual no Expo Go.
