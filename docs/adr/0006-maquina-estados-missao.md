# 0006 — Máquina de Estados de Missão e Lock Pessimista no Aceite

**Data:** 2026-08-06  
**Status:** Aceito

---

## Contexto

O protótipo Flutter descartado creditava a recompensa no momento do **aceite**. Isso permitia aceitar
uma missão, nunca executá-la e ficar com o dinheiro — furo que invalida as três moedas do ADR 0004:
se BRL sai da carteira sem contrapartida, o ledger deixa de descrever trabalho realizado.

O ciclo de vida também precisa responder a três perguntas que o protótipo não respondia:

1. **Quem pode fazer o quê.** Sem isso, qualquer usuário autenticado cancelaria missão alheia (IDOR).
2. **O que aconteceu.** Sem trilha, uma disputa entre criador e executor não tem como ser arbitrada.
3. **Quem vence uma corrida.** Duas pessoas aceitando a mesma missão ao mesmo tempo não podem ambas
   virar executoras — `executor_id` é uma coluna só, e o último UPDATE sobrescreveria o primeiro em
   silêncio.

O `StatusMissao` herdado (`CRIADA, DISPONIVEL, ACEITA, EM_ANDAMENTO, CONCLUIDA, CANCELADA,
EXPIRADA`) não tinha estado para "executor diz que entregou, criador ainda não confirmou" nem para
disputa — sem eles, confirmar seria um salto direto de EM_ANDAMENTO para CONCLUIDA, sem ponto de
contestação.

---

## Decisão

**Adotamos uma máquina de estados explícita, com a tabela de transições declarada dentro do próprio
`StatusMissao`, como único caminho de mudança de status.**

Nove estados e exatamente doze transições:

```
RASCUNHO               --PUBLICAR(criador)-->          ABERTA
ABERTA                 --ACEITAR(candidato)-->         ACEITA
ABERTA                 --CANCELAR(criador)-->          CANCELADA
ABERTA                 --EXPIRAR(sistema)-->           EXPIRADA
ACEITA                 --INICIAR(executor)-->          EM_ANDAMENTO
ACEITA                 --DESISTIR(executor)-->         ABERTA
ACEITA                 --CANCELAR(criador)-->          CANCELADA
EM_ANDAMENTO           --CHECKIN(executor)-->          AGUARDANDO_CONFIRMACAO
AGUARDANDO_CONFIRMACAO --CONFIRMAR(criador)-->         CONCLUIDA
AGUARDANDO_CONFIRMACAO --CONTESTAR(criador)-->         EM_DISPUTA
EM_DISPUTA             --RESOLVER_CONCLUIR(admin)-->   CONCLUIDA
EM_DISPUTA             --RESOLVER_CANCELAR(admin)-->   CANCELADA
```

`CONCLUIDA`, `CANCELADA` e `EXPIRADA` são terminais — ausentes da tabela de propósito.

Decisões que compõem essa escolha:

**Crédito só existe em CONCLUIDA.** É a inversão direta do furo do protótipo, e a razão de
`CONCLUIDA` ser alcançável apenas por `CONFIRMAR` (criador) ou `RESOLVER_CONCLUIR` (admin) — nunca
por ação unilateral de quem recebe.

**A tabela vive no enum, não numa classe separada.** "Quais estados existem" e "como se sai de cada
um" ficam no mesmo arquivo; não há como acrescentar um estado e esquecer de declarar suas saídas.
Como o construtor de um enum não pode referenciar outras constantes do mesmo enum (*illegal forward
reference*), a tabela é preenchida num bloco `static`. `EventoMissao` nunca referencia
`StatusMissao`, o que elimina ciclo de inicialização entre os dois.

**Autorização (403) é checada ANTES da transição (409).** Na ordem inversa, a diferença entre as duas
respostas seria um oráculo: um estranho descobriria o status de uma missão alheia observando qual
erro recebe.

**O ator vem sempre do JWT**, via `AtorMissao`, com papel derivado da authority `ROLE_ADMIN` do
`SecurityContext`. Não usamos `PapelUsuario` porque ele mora em `identidade/dominio` e a regra
ArchUnit proíbe `missoes` de acessá-lo.

**Toda transição grava `missao_evento` na mesma transação** que salva a missão. Status e trilha não
têm como divergir, e o job de expiração usa a mesma máquina (com ator `SISTEMA`), sem caminho
paralelo capaz de contornar as regras.

**O aceite concorrente é serializado por lock pessimista** (`@Lock(PESSIMISTIC_WRITE)` em
`MissaoRepository.buscarParaAtualizar`, ou seja `SELECT ... FOR UPDATE`), não por `@Version` com
retry:

- Com `@Version`, o perdedor só descobre a colisão no *flush/commit*. A
  `ObjectOptimisticLockingFailureException` nasce no interceptor transacional, depois de o método de
  negócio já ter retornado, e o `INSERT` em `missao_evento` do perdedor já foi emitido para ser
  desfeito no rollback. Traduzir isso num 409 com mensagem de negócio exige um handler de
  infraestrutura.
- Com `FOR UPDATE`, o perdedor bloqueia por microssegundos, relê a linha já `ACEITA` e cai na mesma
  `TransicaoInvalidaException` de qualquer transição inválida. **Um caminho de erro, não dois.**
- Retry seria pior: o estado já mudou para `ACEITA`, então repetir a operação nunca sucede. Retry só
  faz sentido quando a colisão é espúria.
- A contenção é por linha e brevíssima.

`@Version` permanece na entidade como defesa em profundidade para os caminhos que não travam a linha
(PATCH), com handler dedicado devolvendo 409.

**Recompensa e categoria são imutáveis após a criação.** O `PATCH` não as expõe. Alterar o prêmio com
a missão já `ABERTA` mudaria o contrato sob os pés de quem está prestes a aceitar.

**Endpoints de fases futuras publicam o contrato de erro definitivo.** `POST /{id}/checkin` (F6),
`/{id}/confirmar` e `/{id}/resolver` (F7) validam autorização e transição e só então respondem
**501**. A ordem 403 → 409 → 501 é testada. O app mobile integra a semântica de erro agora; F6 e F7
só trocam o corpo do método.

---

## Consequências

**Positivas:**

- Aceitar deixou de ser um evento financeiro. O único ponto do sistema que pode creditar carteira é
  a entrada em `CONCLUIDA`, e ela exige ação do criador ou de um admin.
- A matriz completa de 9 estados × 11 eventos = 99 combinações é testável sem Spring, sem banco e sem
  HTTP, em milissegundos. A tabela esperada do teste é escrita à mão, independente do enum — apagar
  uma transição quebra o teste em vez de passar despercebido.
- Aceite concorrente tem um único vencedor comprovado com 50 threads reais sobre HTTP: 1×200,
  49×409, zero 429, zero 500, e exatamente um evento `ACEITA` na trilha.
- `missao_evento` dá base factual para arbitrar disputas: quem desistiu, quando, com que motivo.
- Adicionar um estado novo é uma linha no enum e uma linha no bloco `static` — mais o `CHECK` do
  banco, que continua sendo a segunda barreira.

**Negativas / trade-offs:**

- `SELECT ... FOR UPDATE` **precisa** ser a primeira leitura da transação. Se a entidade já estiver
  no persistence context, o Hibernate devolve a instância em cache sem reemitir o SELECT e o lock
  nunca é adquirido — falha silenciosa que só aparece sob concorrência. Está documentado no
  repositório e no service, mas depende de disciplina.
- O lock pessimista serializa aceites da mesma missão. Irrelevante nesta escala; num cenário de
  milhares de aceites simultâneos na mesma linha, viraria gargalo.
- ~~`RASCUNHO` não tem transição de cancelamento — a máquina implementa apenas as 12 transições
  especificadas. Um rascunho se edita ou se abandona; `DELETE /missoes/{id}` ficou fora do escopo.~~
  **Revisto na F5 (2026-08-07): `RASCUNHO --CANCELAR--> CANCELADA` foi acrescentada, e a máquina
  passou a ter 13 transições.** O motivo é econômico, não de usabilidade: missão comunitária é
  financiada ANTES de publicar (a publicação exige pote cobrindo a recompensa), então "abandonar um
  rascunho" deixou de ser gratuito — prenderia os tokens dos co-financiadores para sempre, já que o
  estorno do pote só roda em `CANCELADA` e `EXPIRADA`. Ver ADR 0008.
- A regra econômica TRIBO/COLETA sem BRL está em dois lugares: no validador de classe (400 com o
  campo apontado) e em `ck_missao_economia` (V3). Duplicação deliberada — a segunda é a barreira que
  sobrevive a um bug de aplicação —, mas as duas precisam mudar juntas.
- Três endpoints respondem 501. É contrato honesto, não funcionalidade.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| `@Version` + retry no aceite | O perdedor descobre a colisão só no commit, fora do código de domínio, e o INSERT na trilha já foi emitido para ser desfeito. Pior: o retry nunca sucede, porque o estado mudou de verdade para ACEITA — retry serve para colisão espúria, não para disputa legítima. |
| Spring State Machine | Traz máquina hierárquica, persistência de contexto e listeners para um agregado com 9 estados e 12 transições. Dependência nova, curva de aprendizado e indireção para resolver o que um `EnumMap` resolve em 20 linhas auditáveis numa banca. |
| Índice único parcial em `(missao_id) WHERE status = 'ACEITA'` | Delegaria a corrida ao banco, mas o perdedor receberia `DataIntegrityViolationException`, que precisaria ser traduzida em 409 por um handler genérico — e um `UPDATE` na mesma linha não dispara a constraint da forma esperada, já que a unicidade seria sobre a própria linha. |
| Status como coluna livre, sem CHECK e sem máquina | Foi o que o protótipo fez. Qualquer código conseguia gravar qualquer string, e nada impedia o salto de ABERTA direto para CONCLUIDA. É precisamente o furo que esta fase fecha. |
| Máquina de estados numa classe separada do enum | Permite que alguém acrescente um estado ao enum e esqueça de declarar suas saídas — o estado nasce órfão e o erro só aparece em runtime. Manter tabela e constantes no mesmo arquivo torna o esquecimento visível na revisão. |
| Argument resolver de `Pageable` do Spring Data na listagem | Aceita `sort=qualquerPropriedade`, expondo nomes do modelo interno e permitindo ordenar por coluna sem índice — DoS barato. Trocado por enum whitelist de ordenação. |
| Serializar `Page`/`PageImpl` direto na resposta | Formato interno do Spring Data, instável entre versões, e vazaria `pageable`/`sort.unsorted` para o app. Trocado por `PaginaResponse`, envelope próprio e estável. |

---

## Notas de manutenção

- **Numeração de migrations:** `db/seed` e `db/migration` são duas *locations* do mesmo Flyway nos
  perfis `dev` e `test`, e a ordem é por número de versão, não por pasta. Todo script de `db/seed`
  precisa de versão **maior** que a última de `db/migration`, ou o seed rodaria antes da migration
  que ele pressupõe.
- **ATUALIZADO em 2026-08-06 — a regra acima passou a ser garantida por construção.** Quando este
  ADR foi escrito, o seed era `V9`/`V10`, no meio da faixa do schema, e as duas notas originais
  diziam que ambos eram "intocáveis" porque editá-los quebraria o checksum de bancos existentes.
  O arranjo era frágil por outro motivo: um `V9__*.sql` novo em `db/migration` derrubaria dev e
  test com *"Found more than one migration with version 9"*, e o erro não apontaria para `db/seed`.
  Os dois seeds foram então consolidados em **`V900__seed_dev.sql`**. A faixa 900+ mantém o seed
  sempre por último, e `db/migration` pode crescer V12, V13... sem colisão possível.
- **Efeito colateral aceito:** rodando por último, o seed grava dados em forma final (`'ABERTA'`,
  senha já com `{bcrypt}`), então os `UPDATE` de renomeação da V11 não afetam mais nenhuma linha em
  dev/test e nenhum teste exercita esse caminho. Foram mantidos por continuarem corretos para
  bancos legados. A alternativa — preservar a cobertura de um caminho que não pode mais ocorrer —
  custaria manter a armadilha de numeração.
- **Privilégios do role `omnitribo_app` não são verificados por teste.** Dev e test conectam como dono
  do schema, então uma falta de `GRANT` jamais apareceria na suíte. Lacuna conhecida, candidata a F12.
- **Jackson:** o Spring Boot 4.1 autoconfigura o mapper do Jackson 3 (`tools.jackson`). Não existe
  bean de `com.fasterxml.jackson.databind.ObjectMapper` para injetar — quem precisar serializar em
  código deve usar `tools.jackson`.
