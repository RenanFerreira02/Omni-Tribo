# Divergências entre a documentação estratégica e a implementação

**Data:** 2026-08-16 · **Fase:** F13
**Documento confrontado:** `documentacao/Omni-Tribo - Documentação.pdf` (37 páginas), doravante "o
PETI". Texto extraído com `pdftotext -layout`; as citações abaixo são literais.

Divergir de um documento anterior não é falha — é o que acontece quando a implementação encontra a
realidade. O que seria falha é divergir **em silêncio**. Este documento existe para que ninguém
descubra sozinho, lendo o código, que ele não faz o que o PETI diz.

---

## 0. Duas incoerências que me pediram para corrigir, e que não se confirmam

Registro por honestidade de método: recebi a tarefa de corrigir duas incoerências específicas do
documento original. **Fui conferir e nenhuma das duas existe como descrita.**

**"A §3.1 diz 'quatro camadas' e lista cinco."** Não. A §3.1 é *PLAN (PLANO)*, da estratégia dos
5 Ps, e lista seis iniciativas — sem falar em camadas. A lista de camadas está na **§8.1**, diz *"A
arquitetura está dividida em quatro domínios principais"* e enumera **exatamente quatro**:
Arquitetura de Negócios, de Dados, de Aplicação e de Tecnologia. **A contagem está correta.**

**"Os pontos de custódia da §2.1 nunca reaparecem no texto."** Também não. A §2.1 é *STRENGTHS
(FORÇAS)* da SWOT e não introduz pontos de custódia; e a palavra "custódia" aparece **12 vezes** ao
longo do documento.

**Mas há um fato verdadeiro por trás da segunda observação, e ele importa muito.** O PETI nunca fala
em *pontos* de custódia — fala sempre em **custódia por vizinho**, num raio de 50 metros. O conceito
de ponto de custódia comercial, que é o que existe no código, **não aparece uma única vez no
documento**. Não é uma incoerência interna do texto; é a maior divergência entre o texto e a
implementação, e está na §2 abaixo.

---

## 1. Convergências que vale registrar

O PETI acerta em pontos de engenharia que costumam ser decorativos neste tipo de documento, e eles
foram implementados como escritos:

| PETI | Implementação |
|---|---|
| §11.5-A — dois vizinhos aceitam a mesma missão "no exato mesmo milissegundo"; mitigação por *"Pessimistic Locking […] O primeiro clique bloqueia o registro"* | É exatamente o que `MissaoService.aplicar` faz: `SELECT … FOR UPDATE` como **primeira leitura** da transação, e o segundo clique recebe 409 |
| §11.5-B — deadlock; mitigação por *"hierarquia estrita de acesso aos dados (todos os processos devem acessar os recursos exatamente na mesma ordem)"* | Transferência entre carteiras trava as duas **ordenadas por id**; o financiamento segue a ordem global `missao → carteira`. Há teste de concorrência com 100 threads |
| §11 B — *"Abordagem MVP: consultas de distância rodando diretamente no banco principal (PostgreSQL com extensão PostGIS)"* | É a implementação, com índice GiST e `EXPLAIN ANALYZE` como evidência |

**Nota importante:** o PETI **especifica PostgreSQL + PostGIS** e não menciona Firestore, Firebase
ou qualquer banco NoSQL em nenhuma página. A ideia de que teríamos trocado Firestore por PostgreSQL
não procede — nunca houve Firestore no documento. Aqui não há divergência: há acordo.

---

## 2. A divergência principal — os 50 metros

O raio de 50 metros aparece **nove vezes** no PETI e sustenta o módulo inteiro de entrega falida:

> *"o app detecta automaticamente quais membros certificados da 'Tribo' (vizinhos com alta reputação
> na plataforma) estão presentes em casa em um raio de 50 metros do endereço de entrega. Quando o
> destinatário original está ausente, o sistema propõe uma 'Missão de Recebimento', permitindo que um
> vizinho de confiança receba o pacote em seu lugar"* — §1.3

**A implementação não faz isso.** O pacote vai para um **ponto de custódia** — loja, locker,
portaria — com capacidade e ocupação controladas sob `FOR UPDATE`, e a missão criada é de
**retirada** nesse ponto, não de recebimento na casa de um vizinho.

**Por quê**, em três razões, todas registradas no
[ADR 0020](adr/0020-ponto-de-custodia-comercial-e-proximidade-por-tribo.md):

1. **"Está em casa" não é observável.** O sistema não sabe se alguém está em casa. Saberia se
   rastreasse localização contínua em segundo plano — consentimento permanente de rastreamento em
   troca de um benefício ocasional. É exatamente a coleta que a §10.3 do PETI classifica como
   *"dados de extrema sensibilidade, como localização em tempo real (para calcular o raio de 50
   metros)"* e como **ameaça estrutural ao projeto** sob a LGPD. O documento identifica o risco e
   mantém a premissa que o cria; a implementação eliminou a premissa.
2. **Custódia por vizinho concentra responsabilidade civil na pessoa errada.** O próprio PETI
   levanta isso duas vezes (§2.2 e §8.4: *"Segurança Jurídica na Custódia de Pacotes"*), sem
   resolver. Ponto comercial tem relação contratual, horário de funcionamento e endereço fixo.
3. **50 metros é pequeno demais para existir massa crítica** — dependência que o próprio PETI
   identifica como fraqueza estrutural em §2.2.

**Consequência de desenho:** "perto" passou a ser **distância mínima até o ponto**, e o fan-out de
notificação é **por tribo**, não por usuário — a tabela `usuario` não tem coluna geográfica, e usar
o centroide da tribo como posição da pessoa seria a métrica errada (nota de manutenção de
2026-08-14).

---

## 3. Divergências de arquitetura

| # | PETI | Implementação | Razão |
|---|---|---|---|
| 1 | §8.1 *"Arquitetura Orientada a Serviços (SOA), materializada através de uma arquitetura de microsserviços"* | **Monólito modular**, 8 módulos com fronteira verificada por ArchUnit | [ADR 0001](adr/0001-monolito-modular.md). Um time, um deploy. Microsserviço pagaria coordenação distribuída sem ter o problema que ela resolve. A fronteira fica pronta para extrair — ver [ordem de decomposição](diagramas/arquitetura-alvo.md) |
| 2 | §8.2 *"padrão API Gateway para centralizar e proteger o acesso"* | Não existe gateway | Com um serviço, o gateway não centraliza nada. As funções que ele teria — CORS, rate limit, autenticação — estão na cadeia de filtros |
| 3 | §8.2 *"padrão Event-Driven Architecture para processamento assíncrono"* | **Outbox transacional em tabela**, drenada por `@Scheduled` | O padrão é o mesmo; o transporte é uma tabela em vez de broker. Broker foi cortado do MVP de propósito, e a troca depois não muda a semântica |
| 4 | §8.2 *"O motor de análise de afinidade entre usuários utilizará algoritmos de Inteligência Artificial como Serviço (AIaaS)"* | **Não há motor de afinidade.** Há regressão logística em Java puro para **risco de entrega**, treinada no `verify` sobre dados **sintéticos** | [ADR 0022](adr/0022-previsao-de-risco-de-entrega.md). Escolha deliberada por modelo interpretável: numa banca é preciso explicar por que o número saiu — e AIaaS exigiria dados reais que não existem |
| 5 | §5.1 e §8.1 — PaaS, nuvem, *"processamento distribuído […] alta disponibilidade"* | **Tudo local**: um Postgres em container, um processo Spring Boot | Escopo declarado do projeto |
| 6 | §5.3 SLA e §11.2 *"Disponibilidade (SLA): 99,9%"* | **Nenhum SLA é medido.** Não há observabilidade: sem métricas exportadas, sem tracing, sem alerta | Prometheus e Grafana foram cortados do MVP. Afirmar 99,9% sem medir seria pior que não afirmar |
| 7 | §11.2 *"até 50.000 conexões WebSocket abertas simultaneamente"* | **Não existe WebSocket algum.** A comunicação é REST com *polling*, e notificação é linha em `alerta` | Nunca houve decisão de transporte em tempo real. É a premissa mais distante da implementação |
| 8 | §11 B — *"Abordagem Escalável: […] coordenadas em tempo real dos usuários migram exclusivamente para o Redis"* | Sem Redis. Cache é **Caffeine local**, em processo | Redis cortado do MVP. Com N instâncias, viraria requisito real — está na [arquitetura-alvo](diagramas/arquitetura-alvo.md) |
| 9 | §11.2 — latência < 200 ms e 1.000 TPS | **Não medidos.** Os testes de carga são a F12b, pendente | Nenhum número de desempenho é afirmado sem medição |

### Terceiros: proxiados, e isso é uma escolha

O PETI prevê integração *"via Logística como Serviço (LaaS), utilizando Webhooks"* (§8.2) — o
webhook de transportadora existe e é autenticado por HMAC sobre o corpo bruto
([ADR 0021](adr/0021-verificacao-de-webhook-de-transportadora.md)).

O que o PETI não trata é **como o app fala com terceiros**. A implementação decidiu: **o app nunca
fala direto**. Clima (Open-Meteo) e CEP (ViaCEP) passam pela nossa fronteira
([ADR 0011](adr/0011-dependencias-externas-e-anonimizacao.md)), atrás de cache → disjuntor →
bulkhead → retry ([ADR 0023](adr/0023-resiliencia-de-integracoes-externas.md)). Falha do provedor
vira **503 uniforme** e a UI **esconde o recurso** em vez de mostrar erro.

Exceção deliberada: os *tiles* do mapa vêm do OpenStreetMap direto no WebView. Proxiar imagem de
mapa não traria benefício e criaria um caminho de banda que nada exige.

---

## 4. A correção da premissa econômica — a divergência que mais importa

O PETI descreve *"uma economia do cuidado por meio de uma moeda social, recompensando comportamentos
comunitários positivos"* (§3.1) e não diz quem paga. Um ADR anterior deste projeto — o 0004 —
preencheu esse silêncio com três moedas e permitiu que **o criador da missão pagasse a recompensa em
BRL**.

**Isso estava errado, e o erro só apareceu quando foi executado.** Uma auditoria criou missões com
recompensa escolhida pelo cliente e mediu o total do sistema subir de 656 para 2.656 tokens —
enquanto o endpoint de reconciliação continuava respondendo `integro=true`.

A correção está no [ADR 0009](adr/0009-economia-do-cuidado-token-como-recompensa.md), que **substitui
a tabela de moedas do 0004**:

- **quem cria a missão não paga.** A recompensa é XP + TOKEN, **calculada pelo servidor e congelada
  na criação** — o DTO de criação não tem `xpRecompensa` nem `tokensRecompensa`;
- **BRL sai do ciclo de missões.** `ck_missao_economia` exige `valor_brl = 0` em toda missão;
- **TOKEN é a recompensa de todas as categorias**, resgatável em benefício de parceiro do bairro.

A história completa — como foi detectado, por que a reconciliação não pegou, e o que continua aberto
— está em [`EVOLUCAO-ARQUITETURAL.md`](EVOLUCAO-ARQUITETURAL.md). **É a parte do projeto que mais
vale ler.**

---

## 5. O que fazer com o PETI

O PDF **não é fonte de verdade técnica** e não é atualizado junto com o código. Se ele for
reapresentado, três correções mínimas o alinhariam com o que existe:

1. **§1.3 e §11** — substituir "vizinho num raio de 50 metros" por **ponto de custódia**, e retirar
   os 50 metros das premissas numéricas, onde hoje justificam latência e Redis.
2. **§8.1 e §8.2** — declarar o **monólito modular** como decisão de MVP, com microsserviços,
   gateway e broker como evolução, e não como estado atual.
3. **§11.2** — separar o que é **medido** do que é **meta**. Latência, TPS, SLA e conexões WebSocket
   são metas sem medição; apresentá-las como dimensionamento sugere uma verificação que não houve.

Nada disso reduz o documento: ele foi escrito antes do código, que é justamente quando um plano deve
ser escrito. O que este arquivo impede é que os dois sejam lidos como se dissessem a mesma coisa.
