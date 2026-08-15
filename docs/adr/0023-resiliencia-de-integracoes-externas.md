# 0023 — Resiliência das integrações externas: disjuntor próprio, retry nativo do Framework 7

**Data:** 2026-08-15
**Status:** Aceito

---

## Contexto

ViaCEP e Open-Meteo são provedores externos atrás da nossa fronteira (ADR 0011). Antes desta
decisão, a proteção deles era parcial e o próprio código dizia isso: o javadoc de
`LimiteDeChamadasExternas` afirmava, textualmente, que *"não há circuit breaker de verdade aqui — o
provedor continua sendo consultado durante a queda, só que por no máximo N threads de cada vez"*.

O que já existia, e continua valendo:

- **timeout** de `PT2S` em conexão e leitura, num `RestClient.Builder` compartilhado;
- **cache** Caffeine por provedor, que deliberadamente **não** guarda falha;
- **bulkhead** por `Semaphore`, uma instância por provedor;
- **fallback** definido: 503 com `type` `servico-externo-indisponivel`, e a reação de UI é esconder
  o recurso.

Faltavam duas coisas: **parar de chamar** um provedor comprovadamente fora do ar, e **repetir** o
que é transitório sem nunca repetir o que é definitivo. E faltava uma decisão sobre biblioteca.

**A restrição que domina a escolha é o Spring Boot 4.1.** O projeto está em Boot 4.1.0 e Framework
7.0.8. Isso descarta boa parte do que a literatura recomenda por padrão.

**É trabalho acadêmico a ser defendido oralmente.** Uma dependência que "funciona mas ninguém sabe
por quê" é pior, aqui, do que cem linhas que se explicam.

---

## Decisão

**Implementamos um circuit breaker próprio, com `Clock` injetado, e usamos o `RetryTemplate` NATIVO
do Spring Framework 7 para a repetição. Nenhuma dependência de runtime nova entra no projeto.**

### 1. A verificação de compatibilidade que fundamenta a escolha

Antes de decidir, medimos — consultando o Maven Central e o repositório do projeto, não a memória:

| Verificação | Resultado |
|---|---|
| `resilience4j-spring-boot4` existe? | **Não.** Não há artefato com esse nome no Maven Central |
| Versão mais recente de `resilience4j-spring-boot3` | **2.4.0**, publicada em **2026-03-14** |
| A 2.4.0 declara suporte a Boot 4? | **Sim** — "Add support for Spring Boot 4 / Spring Cloud 5" (PR #2384, issue #2351 fechada) |
| Quando saiu o Boot 4.1.0? | **2026-06-25**, três meses e meio **depois** |
| O Framework 7 tem circuit breaker nativo? | **Não.** Só `@Retryable`, `@ConcurrencyLimit` e `RetryTemplate` |

A leitura honesta: **existe uma versão que declara suportar Boot 4, mas ela foi publicada contra a
série 4.0.x, e a combinação com a 4.1 não foi verificada por ninguém** — nem pelo projeto do
Resilience4j, nem por nós. Usá-la seria apostar, e a aposta compraria pouco: precisaríamos dela
apenas para o circuit breaker, já que o retry o Framework 7 entrega nativamente e o bulkhead já
existe em vinte linhas testadas.

### 2. O disjuntor: falhas consecutivas, uma sonda, relógio injetado

`DisjuntorDeChamadasExternas` (`integracoes/infra`, package-private), uma instância por provedor.

- **Contagem de falhas CONSECUTIVAS** (limiar 5), zerada por qualquer sucesso. Uma janela por tempo
  não abriria no cenário que mais importa — falhas espalhadas de madrugada, sem sucesso no meio — e
  uma janela por razão exigiria um segundo parâmetro (volume mínimo) que ninguém calibraria.
- **`AtomicReference` sobre um record imutável**, com CAS. Um `synchronized` no método serializaria
  todas as chamadas ao provedor numa thread só, destruindo o bulkhead de 8 permissões logo abaixo.
- **Uma sonda em meia-abertura**, controlada por um token no estado. Sem ele, mil threads chegando
  em meia-abertura viram mil sondas e a meia-abertura vira abertura total.
- **Três desfechos, não dois:** sucesso, falha, e **neutro**. O neutro devolve o token da sonda sem
  mexer no contador, e cobre 4xx, erro de desserialização e a recusa do bulkhead. Sem ele, uma sonda
  recusada pelo bulkhead deixaria o disjuntor preso em meia-abertura **para sempre** — nunca
  fechando, nunca reabrindo, com o provedor de volta e o recurso ainda sumido da tela, sem uma linha
  de erro no log.
- **`Clock` injetado**, e a transição por tempo é preguiçosa, avaliada na entrada. É o que permite ao
  teste avançar 31 segundos em vez de dormir.

### 3. O que conta como falha, e o que não conta

Concentrado em `ClassificacaoDeFalhaExterna`, porque as respostas **não coincidem** entre retry e
disjuntor:

| Situação | Repete? | Conta contra o provedor? |
|---|---|---|
| Timeout (`ResourceAccessException`) | sim | sim |
| 5xx | sim | sim |
| 429 | **não** | **sim** |
| Demais 4xx | não | não |
| Erro de desserialização | não | não |
| Recusa do nosso bulkhead | não | não |
| **ViaCEP `{"erro":true}`** | não | **é SUCESSO** |

O último é o mais importante e é garantido **estruturalmente**: a checagem de `erro` fica FORA da
região protegida, porque o provedor respondeu 200 e está saudável. Movê-la para dentro faria cinco
usuários digitando CEP errado abrirem o disjuntor de um provedor perfeitamente são, derrubando a
busca de CEP para todo mundo. Há teste dedicado a isso.

### 4. A ordem de composição

```
cache (dominio) → DISJUNTOR → BULKHEAD → RETRY → HTTP
```

- **Disjuntor por fora do bulkhead**: com o circuito aberto, a chamada morre antes de tocar o
  semáforo, e "provedor morto não custa nada" vira propriedade literal.
- **Bulkhead por fora do retry**: preserva a invariante que o javadoc do bulkhead já afirmava — uma
  permissão retida significa uma thread nossa presa neste provedor. Uma thread dormindo no backoff
  está presa do mesmo jeito.
- **Retry por dentro do disjuntor**, e esta é a decisão central: uma rajada de tentativas conta como
  **uma** falha lógica. Com o retry por fora, duas tentativas incrementariam o contador duas vezes e
  o circuito abriria na metade do limiar configurado — o número no YAML deixaria de significar o que
  diz, e mudar o número de tentativas re-sintonizaria o disjuntor em silêncio.
- **Tradução para 503 na borda mais externa**: retry e disjuntor precisam ver a exceção original para
  classificar. Converter antes cegaria os dois.

### 5. `invoke`, nunca `execute`

`RetryTemplate` tem dois métodos, e a escolha não é estilística. Verificamos no bytecode que
`invoke(Supplier)` captura o `RetryException`, extrai a causa e **relança a exceção original**;
`execute(Retryable)` lança um `RetryException` **checado**. Com `execute`, o disjuntor veria
`RetryException` em vez de `HttpServerErrorException`, classificaria tudo como "não é falha do
provedor" e **nunca abriria** — sem nenhum teste ficar vermelho.

---

## Consequências

**Positivas:**

- Zero dependência de runtime nova; nada a reavaliar quando o Boot subir de versão.
- O teste de recuperação é **determinístico e instantâneo** (10 casos em 0,135 s), porque o tempo é
  um valor injetado e não uma espera real. Alinhado ao precedente do projeto de escolher
  determinismo sobre conveniência (`StrictMath`, ordem de iteração — ver ADR 0022).
- Cada transição de estado é defensável oralmente, linha por linha.
- O bulkhead, o cache e o contrato de erro existentes continuaram intactos: `dominio/` e `api/` não
  mudaram uma linha.

**Negativas / trade-offs:**

- **É código nosso, e código nosso tem bugs nossos.** Um breaker de biblioteca já foi exercitado por
  milhares de projetos; o vazamento do token de sonda, por exemplo, é um defeito que só não entrou
  em produção porque foi previsto e testado.
- **Sem métricas de breaker no actuator.** O Resilience4j publicaria estado e contadores no
  Micrometer de graça. Hoje o sinal é o log (`WARN` na abertura, `INFO` no fechamento). Aceitável
  num MVP local sem Prometheus — que o CLAUDE.md cortou de propósito.
- **Provedor oscilante nunca abre o circuito.** Falha, ok, falha, ok mantém o contador zerado. É a
  decisão certa aqui (metade das requisições está sendo atendida), mas é uma limitação real.
- **O pior caso de ocupação de thread subiu de ~2 s para ~4,2 s** por chamada lógica (2 tentativas de
  2 s mais o backoff). Fica contido porque o bulkhead segue em 8 permissões por provedor e o
  disjuntor abre em 5 falhas. Baixar `app.integracoes.timeout` para `PT1S` devolveria o pior caso a
  ~2,2 s e é a próxima calibração óbvia, deixada em aberto por não ser necessária.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| **Resilience4j `spring-boot3` 2.4.0** | Publicada em 2026-03-14, **antes** do Boot 4.1.0 (2026-06-25). O suporte a Boot 4 que ela declara foi escrito contra a série 4.0.x e a combinação com a 4.1 não é verificada por ninguém. Compraria só o breaker — retry e bulkhead já temos — ao custo de uma dependência e de um risco não medido |
| **`@Retryable` por AOP em vez de `RetryTemplate` programático** | O proxy decidiria a política por anotação, longe do ponto onde o disjuntor a compõe. A ordem exata (retry POR DENTRO do disjuntor) é a decisão central desta ADR, e com AOP ela ficaria implícita numa ordem de advice em vez de explícita em três linhas |
| **`RetryPolicy.timeout(...)` para limitar a rajada** | Existe e limitaria o pior caso a ~2,2 s. Mas o caminho de aborto constrói um `RetryException` cujo encadeamento de causa precisaria ser verificado empiricamente para garantir que o tipo original chega ao classificador. Previsibilidade venceu; a calibração do timeout resolve o mesmo problema sem novidade |
| **Janela deslizante de falhas** | Exige guarda de volume mínimo, sem a qual a primeira falha do dia abre o circuito com amostra de 1. E faria o estado do disjuntor depender de requisições de quarenta minutos atrás, num endpoint de baixo tráfego |
| **`TipoProblema` novo para "circuito aberto"** | O catálogo tem uma URI por REAÇÃO DE UI (ADR 0010), e a reação de "circuito aberto" é idêntica à de "provedor fora do ar": esconder o recurso. Um tipo novo obrigaria o app a aprender uma distinção que não muda nada do lado dele |
| **Escalada exponencial da espera do circuito aberto** | Previsível vale mais que ótimo numa espera que já é de dezenas de segundos, e a escalada acrescentaria um parâmetro sem melhorar nenhum cenário real destes dois provedores |
| **WireMock nos testes** | A versão mais recente é `4.0.0-beta.38`. Beta não entra num projeto que precisa ser defendido em banca. MockWebServer 5.4.0 é estável e dá contagem de requisições reais, que é a asserção central |
