# 0029 — Painel de impacto: composição por portas, e a premissa declarada como premissa

**Data:** 2026-08-23
**Status:** Aceito

---

## Contexto

O projeto prova **integridade** (reconciliação, ledger append-only, 100 threads disputando a mesma
linha), prova **geolocalização** (`EXPLAIN ANALYZE` com índice GiST, check-in antifraude) e prova
**conservação** (ADR 0027: a soma sobe no aporte, desce no resgate, e nada mais a altera).

Não provava **valor**. Não havia lugar no sistema que respondesse *quanto a tese economizou* — e é
essa a pergunta que um parceiro faria, e a que separa "o software funciona" de "o software serve
para alguma coisa".

`GET /api/v1/admin/impacto` responde. Só ADMIN, só leitura, **nenhuma migration**: tudo por agregação
sobre tabelas que já existem.

---

## Decisão 1 — Onde o relatório mora (§1)

Os números vêm de quatro módulos: `entrega_falida` (`logistica`), `missao` (`missoes`), `checkin`
(`geolocalizacao`) e `lancamento`/`carteira` (`carteira`).

**Uma porta nova por dono do dado, e o montador em `compartilhado`.**

```
compartilhado/api/      ImpactoController · ImpactoResponse
compartilhado/dominio/  ImpactoService · ParametrosImpacto · Mediana

logistica/api/          EstatisticasEntregasFalidas
missoes/api/            EstatisticasMissoes
geolocalizacao/api/     ConsultaPrimeiroCheckin
carteira/api/           EstatisticasToken
```

O precedente é direto: `compartilhado/dominio/DrenadorOutboxService` já injeta
`notificacoes/api/DespachoAlerta`, e `PingController` já vive em `compartilhado/api`. `compartilhado`
está fora do array `MODULOS` do `RegrasArquiteturaTest` e **pode** importar `api/` alheia — o teste
de arquitetura não precisou de uma linha de mudança.

### As duas alternativas descartadas, com o motivo

**Um serviço central lendo as quatro tabelas.** É o que o javadoc de `DadosPessoaisDoUsuario` já
recusou para a exportação LGPD: alcançar `dominio` e `infra` alheios é exatamente o que a regra
proíbe, e `compartilhado/infra` é adaptador privado fechado a todo mundo.

**Hospedar em `logistica`**, cuja tese o painel mede. Recusado por risco concreto: `logistica ↔
missoes` já têm dependência mútua de `api/`, e o `CLAUDE.md` registra que existem **duas** classes de
serviço em `logistica/dominio` justamente para não fechar o ciclo de bean `MissaoService →
EntregaFalidaService → MissaoService`. Somar `carteira` e `geolocalizacao` a esse nó por causa de um
relatório é pagar acoplamento permanente por uma tela de leitura. Em `compartilhado` o acoplamento
aponta todo para quem já é dependência de todos.

Cada implementação de porta é **classe própria injetando só o próprio repositório**. Nenhuma chama
outro serviço, então não há ciclo possível — a conveniência de pendurar os métodos no serviço
existente é justamente como ciclo aparece.

---

## Decisão 2 — Snapshot pelo nível de isolamento (§2)

`ImpactoService.apurar()` roda `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`, e as
seis agregações rodam `MANDATORY` — obrigadas a ler dentro dela.

Sob READ COMMITTED **cada statement enxerga um snapshot próprio**. Um resgate acontecendo entre a
leitura do ledger e a dos saldos apareceria queimado no total e ainda presente na carteira; uma
conclusão entre duas contagens daria `concluidas > criadas`. Seriam incoerências aritméticas
visíveis na tela, causadas só pelo instante da leitura — e **um painel que se contradiz é pior que
um painel ausente**, porque destrói a confiança nos números que estão certos.

`ReconciliacaoRepository` resolve o mesmo problema espremendo tudo numa statement só, e o javadoc de
lá explica por quê. Aqui não dá: as consultas moram em módulos diferentes e juntá-las seria o join
cruzando a fronteira. Então o snapshot vem do isolamento. Custo: nenhum. No PostgreSQL uma transação
read-only em REPEATABLE READ é MVCC puro — não bloqueia escritor nenhum e não pode sofrer erro de
serialização, porque não escreve.

---

## Decisão 3 — A mediana é composta, não joinada (§3)

`entrega_falida` é de `logistica`; `checkin` é de `geolocalizacao`. Um `percentile_cont` sobre o join
seria mais curto e **cruzaria a fronteira dos módulos dentro do SQL, onde o ArchUnit não enxerga** —
o tipo de violação que passa em todos os testes e some da revisão.

Em vez disso: `logistica` devolve `(missao_id, recebido_em)` das convertidas, `geolocalizacao`
devolve o primeiro check-in **válido** por missão, e o montador subtrai e chama `Mediana`, função
pura em `compartilhado/dominio`.

Ganho que não era o objetivo e acabou sendo o melhor argumento: a evidência confere a mediana do
endpoint contra um `percentile_cont` escrito à mão no PostgreSQL. **Duas implementações
independentes concordando** vale mais que uma conferida contra si mesma.

Três detalhes que a implementação obrigou a decidir:

- **Missão sem check-in fica FORA da amostra**, em vez de entrar com valor alto. Ninguém apareceu,
  então não há tempo de resposta a medir — atribuir-lhe um número inventaria a medição que falta.
- **Check-in "antes" do webhook também sai.** `recebido_em` é o relógio da TRANSPORTADORA, não o
  nosso; adiantado, produz delta negativo. É ruído de terceiro, não fato do bairro.
- **Check-in REJEITADO não conta.** `checkin` é append-only e guarda as tentativas reprovadas (fora
  do raio, GPS falsificado, cinemática impossível). Contá-las faria uma tentativa fraudulenta
  melhorar o indicador de impacto do produto. Note que a consulta cinemática de antifraude faz o
  oposto — lá toda posição reportada conta, porque ignorar as rejeitadas permitiria lavar a trilha.
  Perguntas diferentes, filtros diferentes, e as duas divergências estão comentadas no código.

---

## Decisão 4 — "Re-entrega evitada" é a missão concluída, renomeada (§4)

A métrica pedida é *re-entregas evitadas = missões de origem SISTEMA concluídas*. É uma definição
correta e vale a pena dizer em voz alta o que ela **não** é:

**Não é uma segunda medição que confirma a primeira.** É a MESMA contagem sob a interpretação de que
a encomenda teria sido re-entregue se o vizinho não a tivesse retirado. Apresentar as duas lado a
lado como se fossem evidências independentes seria a fraude estatística mais fácil de cometer neste
painel — e a mais difícil de perceber, porque os números batem por construção.

Por isso: o javadoc de `ImpactoService` diz isso, o campo `reentregasEvitadas` diz isso, a tela diz
isso em texto corrido, e `ImpactoServiceTest.reentregaEvitadaEhOMesmoNumero` trava a igualdade — se
um dia divergirem, alguém transformou a interpretação em medição sem perceber.

---

## Decisão 5 — A premissa é declarada, e vem com faixa (§5)

`app.impacto.custo-reentrega-brl` é **premissa, não medição**. Este projeto não mediu esse custo e
não tem como medi-lo: não há operação real, logo não há série de custo para observar.

Três consequências de desenho, e as três existem para a mesma coisa:

1. **Vem de configuração, nunca de código.** Um literal no meio de um cálculo em Java pareceria
   resultado — é assim que suposição vira "dado" no slide de alguém. `ImpactoTest` confere o valor
   do painel contra a propriedade resolvida pelo Spring, não contra um número escrito no teste.
2. **A resposta ECOA a premissa.** Um painel que mostra o produto sem mostrar o fator convida quem
   lê a supor que o fator foi medido.
3. **A resposta traz a mesma conta com a premissa em ±50%.** É meia linha de aritmética e muda a
   natureza da afirmação: em vez de um total frágil, uma faixa. **A conclusão defensável não é o
   número do meio — é a ordem de grandeza que sobrevive à faixa.** Se a conclusão muda quando a
   premissa varia pela metade, ela nunca foi sobre o dado.

Note a assimetria com `ParametrosRecompensa` e `ParametrosRisco`, que têm `versao` obrigatória:
aqueles **congelam** o resultado numa coluna, então a versão é o que explica um crédito antigo. Este
só alimenta um relatório recalculado a cada chamada — nada foi gravado sob a premissa velha, e não
há passado para reinterpretar.

---

## Decisão 6 — Sem cache e sem tabela de agregação (§6)

Nem no servidor, nem no cliente.

Uma tabela de agregação seria uma **segunda fonte de verdade** para números cuja única virtude é
serem conferíveis contra o banco. Um cache seria a mesma coisa com prazo de validade: alguém leria
um número de dez minutos atrás achando que é de agora, e o critério de aceite desta entrega — o
painel batendo com uma contagem manual por SQL — deixaria de ser reproduzível.

O custo é uma consulta a mais numa tela que um ADMIN abre raramente. É barato o bastante para não
valer uma segunda verdade, e a tela diz que não há cache, para que a variação entre dois pedidos
seguidos seja lida como frescor e não como defeito.

---

## O quarto desfecho, descoberto por teste

A primeira versão de `ResumoEntregasFalidas` tinha três campos — os três desfechos do webhook
(ADR 0021 e ADR 0024) — e um javadoc afirmando que eles somavam o total.

`ImpactoTest.funilBateComOBanco` reprovou na primeira execução: **6 contra 22 recebidas**.

Existe um quarto estado que o webhook não produz mas o schema permite e o seed V901 usa:
`missao_id` nulo **e** `motivo_recusa` nulo — encomenda recebida, fisicamente na custódia, que nunca
virou missão nem foi recusada. São 16 das 22 linhas do banco de desenvolvimento.

Sem o teste, o painel teria publicado uma taxa de conversão de 27% calculada sobre um denominador
com um grupo invisível dentro, e a leitura natural — "o bairro só responde a um quarto das falhas" —
seria falsa: a maioria daquelas linhas nunca chegou a ser oferecida a ninguém.

`pendentes` virou campo do painel em vez de resto invisível, a identidade voltou a valer com quatro
termos, e a asserção continua no teste — se um quinto estado aparecer, é ali que ele avisa.

---

## O que este painel NÃO prova

- **Não prova que houve economia.** Prova que N encomendas foram retiradas por vizinhos em vez de
  ficarem na custódia. Que isso tenha evitado uma re-entrega é interpretação (§4), e quanto isso
  vale é premissa (§5).
- **Não prova nada sobre operação real.** Todos os dados são de seed e de execução local. A mediana
  de tempo de resposta mede um script de demonstração, não um bairro.
- **Não afirma `aportados − resgatados == emCirculacao`.** Existe saldo legado anterior ao ADR 0024
  — a cunhagem por conclusão de missão e a conversão 1:2 do seed (ADR 0009) — que nenhum aporte
  explica. Transformar a diferença em asserção reprovaria por um motivo que não é defeito. A
  conservação que o projeto **de fato** garante está no ADR 0027 e é outra.
- **Não substitui a reconciliação.** Aquela pergunta se o sistema está íntegro; esta pergunta quanto
  circula. Uma pode passar enquanto a outra mostra número ruim.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Tabela de agregação, atualizada por evento | Segunda fonte de verdade para números cuja única virtude é serem conferíveis contra o banco. Divergir dela viraria um bug de painel invisível. |
| Cache (Caffeine, como o radar) | O radar cacheia consulta geoespacial cara chamada a cada abertura do app; isto é uma tela de ADMIN aberta raramente. Ganharia latência que ninguém sente e perderia a reprodutibilidade da conferência. |
| `percentile_cont` sobre o join `entrega_falida × checkin` | Mais curto, e cruza a fronteira dos módulos dentro do SQL, onde o ArchUnit não enxerga. Além disso, perderia a conferência entre duas implementações independentes (§3). |
| Taxa `0` quando o denominador é zero | "0% de conversão" afirma desempenho ruim; com denominador zero não há desempenho a relatar. Devolve `null`, e a tela mostra travessão. |
| Mediana sem publicar o tamanho da amostra | Mediana de três pontos não é fato sobre a operação, é anedota com aparência de estatística. `amostraMediana` viaja junto, e a tela avisa abaixo de cinco. |
| Média em vez de mediana | Uma missão aceita três dias depois desloca a média o bastante para descrever uma operação que não existe. `MedianaTest.resisteAoExtremo` mostra o caso: média de 14 h contra mediana de 10 min. |
| Um número único de custo evitado, sem faixa | É o ponto que uma banca ataca, e com razão. A faixa custa meia linha e troca uma afirmação frágil por uma análise de sensibilidade. |
| Filtro por período no endpoint | Útil e ausente do backend. Implementá-lo só no cliente produziria número que o servidor não calculou — a mesma classe de erro que `previa-recompensa` existe para evitar do lado das missões. |
| Deixar o painel aberto a qualquer autenticado | Desempenho comercial (quantas transportadoras foram recusadas por falta de saldo, quanto token circula) é informação de negócio, não de produto; e a consulta varre três tabelas inteiras, o que a torna um DoS barato. Mesma razão de `ReconciliacaoController`. |
