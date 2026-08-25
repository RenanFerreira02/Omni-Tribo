# Teste de carga — três cenários contra o ambiente local

**Data:** 2026-08-25
**Ferramenta:** k6 v2.2.0 · **Script:** [`tools/carga/carga.js`](../../tools/carga/carga.js)
**Resultado:** **14.967 requisições, 0 respostas 5xx, 0 erros inesperados.**

---

## 0. O que este documento é, e o que ele não tenta ser

É a medição que faltava para a **F12b**. O `EXPLAIN ANALYZE` mais citado do projeto
([`f6-explain-analyze.md`](f6-explain-analyze.md)) provou que o radar usa o índice GiST — **em
repouso, com uma consulta de cada vez**. Aqui a mesma consulta apanha sob concorrência, junto com a
contenção do lock da carteira e uma rajada no webhook da tese.

**Nada foi ajustado para melhorar número nenhum.** A API subiu no perfil `dev` exatamente como o
projeto a entrega: pool Hikari no default (10), `leitura-por-minuto: 300`, `escrita-por-minuto: 100`,
`requisicoes-por-minuto: 120` no webhook. Onde o limitador barrou antes do banco, **o achado é esse**
— e a seção 5 mostra que os três tetos medidos batem com os configurados, dígito por dígito.

A escala é pequena e está declarada na seção 7. Um teste honesto sobre ambiente pequeno vale mais que
número grande sem contexto.

## 1. Ambiente

| | |
|---|---|
| Máquina | Intel Core i5-13450HX, 16 núcleos, 15 GB RAM |
| SO | Fedora, kernel 7.1.8-200.fc44.x86_64 |
| Banco | PostgreSQL 16.9 + PostGIS 3.5, container único (`postgis/postgis:16-3.5`) |
| Runtime | OpenJDK 21.0.12, Spring Boot 4.1, perfil `dev` |
| Rede | loopback — cliente e servidor na mesma máquina |
| Dados | seed V900–V906, banco recriado com `make reset` imediatamente antes |

Tudo na mesma máquina: **k6, JVM e Postgres disputaram os mesmos 16 núcleos.** Isso deprime o
resultado em vez de inflá-lo, o que é o lado certo do erro para um teste que quer ser defensável.

## 2. Como reproduzir

```bash
make reset
cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
# noutro terminal:
bash tools/carga/executar.sh
```

Três cenários **sequenciais**, 5 min cada, com 30 s de intervalo — sequenciais de propósito: rodar em
paralelo faria um medir o ruído do outro, e o cenário 3 invalida o cache que o cenário 1 mede. Cada
cenário sobe em rampa de cinco patamares de 1 min, porque "onde degrada" só aparece variando a
pressão. A saída bruta e o resumo por patamar ficam em `tools/carga/saida/`.

---

## 3. Cenário 1 — radar geoespacial (`GET /missoes/proximas`)

**10.349 requisições, 100% HTTP 200, zero 429, zero erro.**

| Janela | req/s | p50 | p95 | p99 |
|---:|---:|---:|---:|---:|
| 0–30s | 6,0 | 11,0 ms | 19,5 ms | 25,0 ms |
| 60–90s | 12,3 | 6,7 ms | 13,5 ms | 16,2 ms |
| 120–150s | 24,6 | 2,9 ms | 5,0 ms | 5,5 ms |
| 180–210s | 44,6 | 2,4 ms | 4,5 ms | 5,1 ms |
| 240–270s | 64,6 | 2,3 ms | 4,0 ms | 4,8 ms |
| **270–300s** | **74,6** | **2,2 ms** | **4,3 ms** | **5,1 ms** |

**A latência CAIU enquanto a carga subia 12×.** Não é anomalia: é o JIT compilando os caminhos
quentes e o cache do Postgres se enchendo. O ponto a registrar é o negativo — **não houve joelho**.
Até 74,6 req/s o p95 fica em 4,3 ms e nada degrada.

### O cache por geohash, medido

Metade das iterações repete a mesma coordenada (mesma célula de geohash, precisão 7 → deve sair do
Caffeine); a outra metade varre uma grade de 60×60 células deslocadas de 0,0025° — mais que a
diagonal de 216 m da célula —, forçando o `ST_DWithin` a ir ao índice.

| Janela | quente p50 | frio p50 | quente p95 | frio p95 |
|---:|---:|---:|---:|---:|
| 60–90s | 6,31 ms | 8,22 ms | 7,90 ms | 14,13 ms |
| 120–150s | 2,22 ms | 3,91 ms | 3,28 ms | 5,33 ms |
| 180–210s | 2,01 ms | 3,27 ms | 2,81 ms | 4,86 ms |
| 240–270s | 1,88 ms | 2,95 ms | 2,75 ms | 4,59 ms |
| **Total** | **2,01 ms** | **3,43 ms** | **6,69 ms** | **8,80 ms** |

**O cache economiza ~1,4 ms no p50 (−41%), e a diferença é estável em todos os patamares** — não é
ruído de uma janela. O que ela também diz é o mais tranquilizador: **o caminho FRIO, que é o índice
GiST sob concorrência, custa 3,43 ms no p50 e 8,80 ms no p95 a 75 req/s.** O índice segura. O cache é
uma economia real, não o que impede o sistema de cair.

---

## 4. Cenário 2 — transferências disputando a MESMA carteira

Todas as transferências têm `renan` numa das pontas (marlene→renan, jonas→renan, renan→marlene,
renan→jonas), então **todas disputam a mesma linha de carteira** e exercitam o lock ordenado por id
crescente que [`integridade-transacional.md`](../qualidade/integridade-transacional.md) descreve.
Chave de idempotência única por iteração — chave repetida viraria replay, que devolve 201 sem pedir o
segundo lock e mediria o atalho, não a contenção.

**2.549 requisições: 1.205 × 201, 269 × 422, 1.075 × 429. Zero 5xx, zero deadlock.**

| Janela | req/s | p50 | p95 | 201 | 429 | 422 |
|---:|---:|---:|---:|---:|---:|---:|
| 330–360s | 1,2 | 12,6 ms | 16,3 ms | 35 | 0 | 0 |
| 420–450s | 4,2 | 9,0 ms | 11,7 ms | 126 | 0 | 0 |
| 480–510s | 8,6 | 8,8 ms | 11,6 ms | 223 | 36 | 0 |
| 540–570s | 13,7 | 4,5 ms | 10,9 ms | 100 | 161 | 149 |
| 600–630s | 18,7 | 1,1 ms | 10,0 ms | 101 | 410 | 49 |

**O p95 das transferências que EXECUTARAM não passa de 12,3 ms em nenhum patamar.** O p50 despenca a
1,1 ms no fim porque a maioria das respostas passa a ser 429, que custa quase nada — ler a queda como
"ficou mais rápido" seria erro; o número que importa é o p95 do 201, estável.

**A contenção não produziu um único deadlock nem um único 5xx.** É a confirmação sob carga do que
`TransferenciaDeadlockTest` prova em 100 rodadas de 2 threads: a ordenação por id não reduz a
probabilidade de deadlock, ela o torna impossível.

Os 269 × 422 são **regra de negócio funcionando**, não falha: saldo insuficiente e o teto de 2.000
tokens por janela de 24 h. Com saldos de seed entre 42 e 124 tokens, transferir 1 token por iteração
esgota o que existe — e o sistema recusa antes de escrever, que é o desenho.

---

## 5. Cenário 3 — rajada no webhook de entrega falida

Todos os webhooks apontam para **um único ponto de custódia** (`LM-ARI-001`, capacidade 60, ocupação
inicial 3), porque a conversão trava a linha do ponto com `SELECT … FOR UPDATE`: mandar todos para o
mesmo ponto é o que mede a serialização. Distribuir mediria vazão e esconderia a contenção.

**2.069 requisições: 688 × 200, 1.381 × 429. Zero 5xx.**
**Dos 688 desfechos: 57 `CONVERTIDA`, 631 `RECUSADA`, 0 `SEM_PATROCINIO`.**

| Janela | req/s | p50 | p95 | p99 | 200 | 429 |
|---:|---:|---:|---:|---:|---:|---:|
| 660–690s | 1,2 | 14,7 ms | 112,7 ms | 936,5 ms | 35 | 0 |
| 720–750s | 2,5 | 8,6 ms | 10,2 ms | 13,0 ms | 74 | 0 |
| 780–810s | 4,9 | 7,1 ms | 8,8 ms | 9,3 ms | 121 | 27 |
| 870–900s | 10,9 | 0,8 ms | 8,5 ms | 9,1 ms | 60 | 268 |
| 930–960s | 14,9 | 0,7 ms | 7,5 ms | 8,8 ms | 60 | 387 |

O p99 de **936 ms no primeiro patamar** é carregamento de classe e JIT do caminho do webhook, que só
agora executa pela primeira vez — ele desaparece já no patamar seguinte e não volta. É o único valor
acima de 120 ms na execução inteira.

### O gargalo real do caminho da tese não é o banco — é a vaga

**57 conversões, e o ponto começou com 57 vagas livres (3/60).** Depois disso, todo webhook responde
`RECUSADA`. Conferido no banco ao fim da execução:

```
ponto_ocupacao | LM-ARI-001 60/60
```

A ocupação foi de 3 para exatamente 60. **A capacidade física do ponto de custódia é o limite do
caminho da entrega falida**, e ela se esgota em menos de dois minutos de rajada. Nenhum ajuste de
pool ou de índice mudaria isso — é uma restrição do produto, não da infraestrutura, e é o tipo de
coisa que só um teste de carga mostra.

### Os três tetos medidos batem com os três configurados

É a verificação mais forte desta fase, porque não é aproximada:

| Teto | Configurado | Medido |
|---|---:|---:|
| `app.webhooks.requisicoes-por-minuto` | 120/min | **60 por janela de 30 s = 120/min** |
| `app.rate-limit.escrita-por-minuto` (2 remetentes efetivos) | 100/min cada | **~100 por janela de 30 s = 200/min** |
| `app.notificacoes.alertas-alta-prioridade-por-hora` | 8 | **8 alertas para o único destinatário elegível** |

O fan-out entregou **8 alertas `ENTREGA_FALIDA_DISPONIVEL` de prioridade ALTA a `fernanda`** — o
único usuário do seed com consentimento de NOTIFICACAO e LOCALIZACAO vigente na área. Oito é
exatamente `alertas-alta-prioridade-por-hora`. **O teto por hora funciona, e este é o primeiro
registro dele funcionando sob pressão em vez de em teste unitário.**

### O radar nunca tomou 429, e isso tem explicação

Chama atenção que o cenário 1 chegue a 74,6 req/s sem um único 429, com teto de 300 leituras/min por
usuário e 9 usuários (= 45 req/s em regime permanente). A causa é o **balde**: o bucket4j usa
`refillGreedy(300, 1 min)`, ou seja, capacidade 300 e reposição de 5/s. A rampa passou dos 45 req/s
só nos dois últimos patamares, e 2 minutos acima do teto não bastam para drenar um balde de 300
fichas que os três primeiros patamares encheram.

**O teto de leitura é de regime permanente; o balde absorve rajada.** Uma carga plana de 75 req/s
por mais de ~3 min começaria a receber 429 — não foi medido, e por isso não é afirmado aqui.

---

## 6. Achado: o alerta de ponto lotado não tem teto nem deduplicação

**631 linhas em `alerta`, todas `PONTO_CUSTODIA_LOTADO`, todas idênticas, todas para o mesmo ponto,
em menos de 3 minutos.**

O alerta é **global de propósito** (`usuario_id` nulo — ver `DespachanteAlertaService
.gravarPontoLotado`), e por isso o teto de `alertas-por-hora`, que é por usuário, corretamente não se
aplica: não é notificação de ninguém, é sinal de operação. O javadoc explica bem a intenção: *"um
ponto que recusa encomendas com frequência é exatamente o dado que justifica negociar mais capacidade
ou abrir outro ponto no bairro"*.

**Mas 631 linhas dizendo a mesma frase sobre o mesmo ponto não são esse dado — são o apagamento
dele.** A informação útil ("LM-ARI-001 recusou 631 encomendas entre 07:41 e 07:44") existe implícita
e ninguém a lê; o que a tabela ganha é uma amplificação de escrita sem limite, disparada por um
evento externo que o sistema não controla. Uma transportadora em laço de retry contra um ponto cheio
escreve indefinidamente.

**Não corrigido nesta passada, de propósito** — o pedido era medir sem ajustar, e a correção
(deduplicar por `(ponto, janela)`, ou contador em vez de linha) muda o contrato do alerta
operacional. Fica registrado como o achado principal desta fase.

Contraste que ajuda a dimensionar: a **outbox drenou inteira** — `outbox_pendente = 0` e
`MAX(tentativas) = 0` nos 688 eventos. Nenhum evento chegou perto do limite de 5 tentativas da
Pendência #1.

## 7. O que isto NÃO prova

- **Uma máquina, um processo, um banco.** k6, JVM e Postgres dividiram os mesmos 16 núcleos, em
  loopback. Não há rede real, não há latência entre serviços, não há segundo nó. Nada aqui fala de
  comportamento distribuído, e o projeto recusa bancada distribuída deliberadamente.
- **Dado de seed, não dado de produção.** 13 carteiras, 6 missões abertas, 3 pontos de custódia. As
  consultas geoespaciais varreram um conjunto minúsculo: **o p95 de 8,8 ms do caminho frio diz que o
  índice é usado, não que ele escala.** Com milhões de missões o número seria outro, e este teste não
  autoriza extrapolá-lo.
- **Cinco minutos não é resistência.** Não houve teste de soak. Vazamento de memória, crescimento de
  pool, inchaço de tabela e fragmentação de índice aparecem em horas, não em minutos.
- **Rajada, não regime permanente.** Como a seção 5 explica, o balde do rate limit absorveu a rampa
  do radar. Os tetos de leitura sob carga plana e prolongada **não foram medidos**.
- **O pool de conexões nunca foi pressionado.** Com o limitador barrando antes, o Hikari default de
  10 conexões não chegou a saturar — não há aqui nenhuma evidência sobre o comportamento do pool no
  esgotamento, que é justamente o modo de falha que `application-test.yml` documenta ter encontrado
  com 100 threads.
- **Não mede a fórmula de recompensa nem a previsão de risco sob carga.** O webhook as exercita, mas
  o cenário não isola nem afere o custo de cada uma.
- **Não é comparação antes-e-depois.** Não existe medição anterior deste ambiente, então nada aqui
  diz se o sistema melhorou ou piorou — é a primeira linha de base. As próximas medições é que terão
  esse poder.
- **Os números do documento estratégico continuam sem lastro.** "< 200 ms, 1.000 TPS, SLA 99,9%" são
  metas herdadas; este teste mediu 75 req/s num cenário e 2 req/s noutro, em ambiente de uma máquina.
  Ver [`../DIVERGENCIAS-DOCUMENTACAO.md`](../DIVERGENCIAS-DOCUMENTACAO.md).
