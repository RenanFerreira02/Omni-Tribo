# Evolução arquitetural — como este projeto se corrigiu

**Data:** 2026-08-16 · **Fase:** F13

Um projeto que mostra como se auto-corrigiu vale mais que um que finge ter acertado de primeira.
Este documento conta a linha do tempo das decisões e, no centro dela, **o defeito econômico que uma
auditoria encontrou e que o endpoint de integridade do próprio sistema não conseguia ver.**

A tese é curta: **medir muda decisão; ler, não.** Cinco dos sete defeitos da rodada de auditoria
F0→F7 eram invisíveis na leitura do código. O mais caro deles é o desta história.

---

## 1. Linha do tempo

| Data | Marco | Decisão que ficou |
|---|---|---|
| 2026-08-04 | F0–F1 — fundação e infraestrutura | Monólito modular ([ADR 0001](adr/0001-monolito-modular.md)); PostGIS desde o primeiro dia ([ADR 0002](adr/0002-postgresql-postgis.md)) |
| 2026-08-05 | F2 — bootstrap da API | Erro em RFC 9457 desde o começo, não como reforma posterior |
| 2026-08-06 | F3–F4 — domínio, migrations, autenticação | Ledger **append-only**, correção por estorno ([ADR 0008](adr/0008-ledger-append-only-e-idempotencia.md)); JWT RS256 + Argon2 ([ADR 0005](adr/0005-autenticacao-jwt-argon2.md)) |
| 2026-08-06 | F5 — ciclo de vida da missão | Máquina de estados explícita, transição só por evento ([ADR 0006](adr/0006-maquina-estados-missao.md)) |
| **2026-08-07** | **ADR 0009 — a premissa econômica é corrigida** | **BRL sai do ciclo; quem cria não paga** |
| 2026-08-07 | F6–F7 — geolocalização e carteira | Consultas geoespaciais centralizadas ([ADR 0007](adr/0007-consultas-geoespaciais-centralizadas.md)); ordem determinística de lock |
| 2026-08-07/08 | **Rodada de auditoria F0→F7** | 7 defeitos corrigidos, **5 invisíveis na leitura do código** |
| 2026-08-08 | Recompensa calculada e congelada pelo servidor | `CalculadoraDeRecompensa` pura + `versao_formula` |
| 2026-08-08/09 | F9–F12 — app mobile | Catálogo de erro por **reação de UI** ([ADR 0010](adr/0010-granularidade-do-catalogo-de-tipos-de-problema.md)); mapa por WebView ([ADR 0012](adr/0012-mapa-por-webview-e-leaflet.md)); nada persistido na web ([ADR 0013](adr/0013-persistencia-de-segredo-por-plataforma.md)) |
| 2026-08-09 | Auditoria independente do mobile | 2 defeitos que revisão comum deixou passar: prompt de permissão gasto sem justificativa; 11 de 22 pares de cor reprovando WCAG AA |
| **2026-08-11** | **Verificação de backend — quatro correções estruturais** | Autorização reconferida por requisição ([0016](adr/0016-autorizacao-reconferida-por-requisicao.md)); papéis de banco separados ([0017](adr/0017-papeis-de-banco-separados.md)); fronteira do `compartilhado` ([0018](adr/0018-fronteira-do-compartilhado.md)); borda HTTP ([0019](adr/0019-borda-http-cabecalho-nao-confiavel.md)); **estados sem saída** ([0015](adr/0015-destravamento-de-estados-sem-saida.md)) |
| 2026-08-14 | F8 — fim da entrega falida | Ponto de custódia comercial ([ADR 0020](adr/0020-ponto-de-custodia-comercial-e-proximidade-por-tribo.md)); webhook HMAC ([ADR 0021](adr/0021-verificacao-de-webhook-de-transportadora.md)) |
| 2026-08-15 | F12c — previsão de risco | Regressão logística interpretável, dados **sintéticos e declarados** ([ADR 0022](adr/0022-previsao-de-risco-de-entrega.md)) |
| 2026-08-15 | Resiliência e gates | Disjuntor próprio ([ADR 0023](adr/0023-resiliencia-de-integracoes-externas.md)); JaCoCo passa a **barrar** o build |
| 2026-08-16 | F13 — entrega final | Diagramas, comparativo, divergências, e **remedição da conservação** |

---

## 2. O defeito econômico, do começo ao fim

### 2.1 A premissa errada

O documento estratégico fala em *"economia do cuidado por meio de uma moeda social"* e **não diz quem
paga**. O ADR 0004 preencheu o silêncio com três moedas e deixou o criador da missão pagar a
recompensa em BRL.

Ninguém percebeu que isso estava errado lendo o ADR. Ele é coerente por dentro.

### 2.2 Como apareceu: execução, não leitura

A auditoria rodou **três ciclos completos de missão contra a API em execução** e mediu o total de
BRL do sistema:

```
SUM(carteira.saldo_brl) do sistema ANTES:  R$   118,00
  ciclo 1 → executor: R$  565,00  |  criador: R$ 18,00
  ciclo 2 → executor: R$ 1065,00  |  criador: R$ 18,00
  ciclo 3 → executor: R$ 1565,00  |  criador: R$ 18,00
SUM(carteira.saldo_brl) do sistema DEPOIS: R$ 1618,00
```

**R$ 1.500 criados do nada, e o saldo do criador não se moveu em nenhum momento.** Ele não pagou —
o produto nunca previu que pagasse. Não havia escrow na publicação, e o único `DEBITO` de BRL do
sistema inteiro era o saque. Ou seja: **BRL só tinha saída, nunca entrada legítima.**

A leitura do código não pegaria isso, porque cada peça estava certa: o crédito era um lançamento bem
formado, com chave de idempotência única, saldo consistente. **O que faltava não estava em nenhuma
linha — estava na ausência de uma.**

### 2.3 O mesmo defeito, reencarnado no TOKEN

Corrigido o BRL, a auditoria da F0 encontrou a segunda metade: **o cliente escolhia a própria
recompensa.**

```java
// CriarMissaoRequest.java:42,45 — como era
long tokensRecompensa,   // ← enviado pelo cliente, @Max(1000)
int  xpRecompensa,       // ← enviado pelo cliente, @Max(5000)
```

Uma varredura por `calcularRecompensa|RegraRecompensa|complexidade` em todo `src/main/java` **não
retornava nada**: não havia cálculo de recompensa no servidor, em lugar nenhum. Medido contra a API:

```
TOKEN no sistema (carteiras + potes) ANTES:  656
  ciclo 1, tokensRecompensa=1000 escolhido pelo cliente → 1656
  ciclo 2, tokensRecompensa=1000 escolhido pelo cliente → 2656
TOKEN no sistema DEPOIS: 2656          (+2000 do nada)

Reconciliação: {"carteirasVerificadas":6,"integro":true,"divergencias":[]}
```

Dois usuários combinados emitiam moeda à vontade.

### 2.4 Por que a reconciliação não pegou — e por que ela estava certa

Esta é a parte que vale a leitura.

`ReconciliacaoService` executa **uma única consulta**: para cada carteira, compara o saldo projetado
com a soma dos lançamentos do ledger.

```sql
-- essência da consulta
SELECT ... FROM carteira c
LEFT JOIN (SELECT carteira_id,
                  SUM(CASE WHEN sinal='CREDITO' THEN valor ELSE -valor END) AS soma
             FROM lancamento GROUP BY carteira_id) l ON l.carteira_id = c.id
WHERE c.saldo_tokens <> l.soma OR c.saldo_brl <> l.soma_brl;
```

**Cunhar token escreve os dois lados.** Insere um `RECOMPENSA_MISSAO` de crédito **e** soma o mesmo
valor no saldo. A igualdade `saldo == SUM(lançamentos)` continua verdadeira — perfeitamente. A
reconciliação não estava quebrada nem mal escrita: **ela responde a outra pergunta.**

| | Pergunta que responde | Tem endpoint? |
|---|---|---|
| **Reconciliação** | "o saldo desta carteira é explicado pelo histórico dela?" | ✅ `GET /admin/carteiras/reconciliacao` |
| **Conservação** | "o total de tokens do sistema mudou sem alguém ter pago?" | ❌ nenhum |

**A lição, em uma frase: uma invariante que ninguém mede não está garantida — e um painel verde pode
estar medindo a coisa errada.**

O mesmo cegamento apareceu depois em outro lugar, o que confirma que não era acidente: quando
`EM_ANDAMENTO` e `AGUARDANDO_CONFIRMACAO` não tinham saída, o pote de quem financiou ficava
imobilizado para sempre numa missão morta — e a reconciliação continuava respondendo `integro=true`,
porque ledger e projeção seguiam batendo ([ADR 0015](adr/0015-destravamento-de-estados-sem-saida.md)).

### 2.5 A correção

Duas metades, e uma só não bastava:

1. **`xpRecompensa` e `tokensRecompensa` saíram do DTO de criação** — mesma disciplina que já havia
   removido `status`, `executorId` e `criadorId`.
2. **O servidor passou a derivar a recompensa**: `CalculadoraDeRecompensa` é função **pura** —
   recebe categoria, complexidade, distância, peso, volume e multiplicador de risco, e devolve XP +
   tokens + `versaoFormula`. A calibração vive em YAML: **a fórmula é código, os números são
   configuração**. O valor é **congelado na criação**, e a conclusão lê o congelado em vez de
   recalcular.

Sem a metade 2, a metade 1 apenas congelaria a recompensa em zero.

Duas consequências de desenho que vieram junto:

- **`POST /missoes/previa-recompensa`** mostra o valor antes de criar, para que o app **nunca**
  reimplemente a fórmula — foi assim que a divergência cliente/servidor foi fechada por construção,
  e não por disciplina.
- **`CalculadoraDeRecompensaTest.douradoV1` falha de propósito** quando um parâmetro do YAML muda sem
  que a `versao` suba. Sem isso, missões antigas passariam a ser explicadas por uma calibração que
  não as produziu, e sumiria a resposta para *"este crédito estava certo quando foi feito?"*.

### 2.6 O que continua aberto — e remedido hoje

> **Superado em 2026-08-22.** Esta seção descreve o estado de **2026-08-16** e fica como está: o
> valor dela é ter sido escrita quando a lacuna existia. O que mudou desde então: a carteira de
> patrocinador chegou ([ADR 0024](adr/0024-carteira-de-patrocinador.md), `V23`), AJUDA passou a pagar
> do pote ([ADR 0025](adr/0025-ajuda-paga-do-pote.md)) e o resgate virou o sumidouro
> ([ADR 0027](adr/0027-resgate-queima-token.md)). A conservação foi remedida nas quatro categorias
> com **Δ=0** ([evidência](evidencias/f14-conservacao-quatro-categorias.md)). A última cunhagem que
> resta é ENTREGA criada por humano, declarada em `FontePote.CUNHAGEM`.

**ENTREGA e AJUDA ainda cunham.** `pagaTokensDoPote` cobre apenas TRIBO e COLETA. Refiz a medição do
zero em 2026-08-16, com o banco recriado
([evidência completa](evidencias/f13-conservacao-por-categoria.md)):

```
BASELINE                            carteiras=689  potes=156  total=845

CICLO AJUDA   (recompensa 30)       total 845 → 875     Δ = +30   ← cunhou
  reconciliação: {"integro":true,"divergencias":0}

CICLO TRIBO   (recompensa 38)       total 875 → 875     Δ = 0     ← conservou
  logo após o financiamento:        carteiras=681  potes=194  total=875
  reconciliação: {"integro":true,"divergencias":0}
```

A linha do meio é o mecanismo inteiro em três números: a carteira de carol caiu 38, o pote subiu 38,
o total não se moveu. E a reconciliação respondeu `integro=true` **nos dois casos**, inclusive
naquele que cunhou 30 tokens do nada.

**Isto não foi contornado por esquecimento, e a razão importa.** Exigir pote para ENTREGA faria
membros da tribo custearem a logística do varejista — o inverso do modelo. O financiador correto é o
**patrocinador**: entrega que falhou custa re-entrega, armazenagem e risco de perder o cliente, então
patrocinar o pote sai mais barato que o fracasso. **Preferiu-se uma lacuna documentada a uma regra
errada codificada.**

Enquanto isso, o dano é limitado por construção: o multiplicador de risco tem teto **1,5×**, declarado
em dois blocos de configuração com um teste travando a concordância entre eles. Sem teto, o risco
multiplicaria a emissão sem financiador.

---

## 3. Outros três casos em que medir mudou a decisão

**O oráculo de tempo no login.** O código parecia correto. Cronometrado, e-mail inexistente respondia
em **~6 ms** e senha errada em **~68 ms** — a diferença vinha de um curto-circuito de `&&` que pulava
o Argon2. Um atacante enumeraria a base de usuários pelo relógio. Só medição acha isso.

**O `REVOKE` inerte.** As migrations revogavam `UPDATE` e `DELETE` do ledger para o papel da
aplicação, e o comentário afirmava que o ledger era imutável. **A aplicação conectava como dono das
tabelas**, e dono ignora `REVOKE`. Ler o código teria confirmado o comentário. Hoje a aplicação
conecta como `omnitribo_app`, o Flyway tem credencial própria, e um teste prova a proibição **em
runtime** (SQLState 42501) em vez de ler o catálogo.

**A conta anonimizada que continuava escrevendo.** Exclusão de conta anonimizava o registro, mas o
JWT já emitido seguia válido por até 15 minutos. Hoje o filtro consulta a sessão a cada requisição
(cache de 60 s) e monta o principal **do banco** — o que também faz o papel ser reconferido.

---

## 4. O padrão

| O que a leitura vê | O que só a execução revela |
|---|---|
| lançamento bem formado, saldo consistente | o total do sistema subiu sem ninguém pagar |
| `REVOKE UPDATE, DELETE` na migration | a aplicação é dona da tabela e ignora o `REVOKE` |
| comparação de senha com Argon2 | o `&&` curto-circuita e o relógio entrega quem existe |
| máquina de estados coerente | dois estados sem saída imobilizam pote para sempre |
| endpoint de integridade respondendo `integro=true` | ele mede outra invariante |

Por isso as auditorias deste projeto têm uma regra central: **medir antes de afirmar** — SQL contra o
banco de pé, `curl` contra a API em execução, e os próprios testes. E por isso cada afirmação de
garantia neste repositório aponta para um arquivo em [`evidencias/`](evidencias/) ou
[`qualidade/`](qualidade/).

---

## Anexo — índice das auditorias

Dez auditorias, uma por fase, cada uma classificando os itens como **DEFEITO**, **LACUNA**,
**DIVERGÊNCIA ACEITÁVEL**, **EXCEDENTE** ou **CONFORME**, com arquivo e linha.

O anexo é **índice, não cópia**: os relatórios estão versionados e são a fonte; duplicar milhares de
linhas aqui criaria duas versões da mesma verdade, que é exatamente o problema que este documento
existe para evitar.

| Auditoria | Data | Itens | Defeitos | Achado mais relevante |
|---|---|---|---|---|
| [F0](auditoria/F0.md) | 2026-08-07 | 6 | **2** | cliente escolhia a própria recompensa; medido 656 → 2.656 tokens |
| [F1](auditoria/F1.md) | 2026-08-07 | 7 | 0 | — |
| [F2](auditoria/F2.md) | 2026-08-07 | 11 | 0 | — |
| [F3](auditoria/F3.md) | 2026-08-07 | 11 | **2** | `REVOKE` inerte; lançamento duplicado no seed |
| [F4](auditoria/F4.md) | 2026-08-07 | 9 | 0 † | † o relatório fecha com "nenhum DEFEITO", mas **a rodada achou um**: o oráculo de tempo no login (~6 ms × ~68 ms), corrigido antes do relatório ser fechado |
| [F5](auditoria/F5.md) | 2026-08-08 | 10 | **1 crítico** | `POST` real criou missão AJUDA sem peso nem volume valendo o teto: 5000 XP / 1000 tokens |
| [F6](auditoria/F6.md) | 2026-08-08 | 10 | 0 | dois bugs de cinemática achados por teste de integração |
| [F7](auditoria/F7.md) | 2026-08-08 | 9 | 0 | **LACUNA média**: TRIBO Δ=0, ENTREGA Δ=+60, reconciliação íntegra nos dois |
| [mobile-fundacao](auditoria/mobile-fundacao.md) | 2026-08-09 | 16 | **2** | prompt de permissão gasto sem justificativa; 11/22 pares reprovando WCAG AA |
| [mobile-completo](auditoria/mobile-completo.md) | 2026-08-09 | 17 | **4** | conta anonimizada escrevendo por 15 min; `nivel` divergente na exportação LGPD |

**Atenção ao ler a coluna "Defeitos":** ela conta o que o relatório **publicado** classificou como
DEFEITO, e vários relatórios foram fechados depois da correção — a F4 é o exemplo. Para saber o que
cada rodada **encontrou**, a fonte são as **Notas de manutenção** de [`PROGRESSO.md`](PROGRESSO.md),
que registram o achado no momento em que ele apareceu.

**Todos os defeitos listados estão corrigidos.** O que permanece aberto é a **lacuna** da F7 — a
cunhagem em ENTREGA e AJUDA —, remedida na §2.6 acima e registrada como Pendência #1.

O log de por que cada correção estrutural foi feita está nas **Notas de manutenção** de
[`PROGRESSO.md`](PROGRESSO.md).
