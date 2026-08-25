# Teste de mutação — `missoes.dominio` e `carteira.dominio`

**Data:** 2026-08-25 · **Ferramenta:** PIT (pitest-maven 1.25.9, pitest-junit5-plugin 1.2.3)
**Escopo:** `com.omnitribo.missoes.dominio` e `com.omnitribo.carteira.dominio` · **Sem gate.**

```bash
cd services/api && ./mvnw -Pmutacao test-compile org.pitest:pitest-maven:mutationCoverage
# relatório navegável em target/pit-reports/index.html
```

---

## 0. Por que sem gate, e por que só estes dois pacotes

O escopo já estava decidido no `CLAUDE.md` antes de a ferramenta existir: *"restrito a
`missoes.dominio` e `carteira.dominio`, e sem gate: é ali que o teste protege dinheiro e máquina de
estados, e é ali que um teste sem assertion passaria despercebido"*. O PIT só formaliza uma prática
que o projeto já tinha à mão — a auditoria do mobile mutou `rotacaoCompartilhada()` fora da árvore
para provar que o teste de refresh concorrente pegava, e o gate do JaCoCo foi verificado subindo o
mínimo para 0,99 para confirmar que reprovava por razão real.

**O número não é a entrega. Os sobreviventes são.** Um gate reprovaria o build por mutante
equivalente em getter, e o valor está em quais assertions faltam — que é o que a seção 3 lista.

O profile `mutacao` fica **fora do `verify`**, pela mesma razão do `seguranca`: mutar exige reexecutar
a suíte uma vez por mutante, e prender isso ao build acabaria com o ciclo rápido de que a suíte
depende para ser rodada a cada mudança. Custo medido: **5m49s**, contra 1m07s do `verify`.

## 1. O resultado

| Pacote | Mutantes | Mortos | Score |
|---|---:|---:|---:|
| `com.omnitribo.missoes.dominio` | 311 | 235 | **75,6%** |
| `com.omnitribo.carteira.dominio` | 183 | 114 | **62,3%** |
| **Total** | **494** | **349** | **70,6%** |

Cobertura de linha das classes mutadas: **1183/1250 (95%)**.
Status: 349 `KILLED`, 97 `SURVIVED`, 48 `NO_COVERAGE`.

## 2. A primeira configuração estava errada, e o relatório denunciou

Vale mais que o score, porque é um erro que qualquer um repetiria.

A configuração inicial restringia `targetTests` a `com.omnitribo.missoes.*` e
`com.omnitribo.carteira.*` — o que parece óbvio, já que o alvo é o domínio desses dois módulos. O
resultado:

```
AporteService   0/7 mutantes mortos   (7 × NO_COVERAGE)
```

**`AporteService` é o único ponto de emissão de token do sistema inteiro.** E ele tem teste:
`PatrocinadorAdminTest`, que mora em `identidade.api` — fora do filtro. O mesmo valia para
`FinanciamentoCarteiraService.debitarPatrocinador`, exercitado pelo webhook em `logistica.api`.

Ou seja: o filtro produzia um relatório que acusava de **intestado justamente o código mais sensível
do projeto**. É o espelho exato do `<includes>` vazio do JaCoCo, que o comentário do `pom.xml` já
avisa que "passa por vácuo" — e a mesma lição, aprendida de novo por outro caminho.

Removido o filtro:

| | com filtro | sem filtro |
|---|---:|---:|
| Mutantes mortos | 315/494 (64%) | **349/494 (71%)** |
| Cobertura de linha das mutadas | 84% | **95%** |
| `AporteService` | 0/7 | **4/7** |
| Tempo | 4m47s | 5m49s |

## 3. Os sobreviventes que interessam

### 3.1 Quatro fronteiras de saldo e de teto sem teste no valor EXATO

O padrão mais consistente do relatório: `ConditionalsBoundaryMutator` sobrevivendo em toda checagem
de saldo e de limite. Trocar `<` por `<=` não é pego porque **nenhum teste passa exatamente no valor
da fronteira**.

| Local | Código | O que o mutante sobrevivente significa |
|---|---|---|
| `TransferenciaService:100` | `if (origem.getSaldoTokens() < tokens)` | Ninguém transfere **exatamente o saldo inteiro**. Com `<=`, transferir tudo o que se tem passaria a ser recusado — e nenhum teste notaria |
| `ResgateService:112` | `if (carteira.getSaldoTokens() < custo)` | Idem para o resgate: ninguém resgata um benefício que custa exatamente o saldo disponível |
| `CalculadoraDeRecompensa:174` | `informado.compareTo(minimo) < 0` | Nenhum teste passa multiplicador de risco **exatamente 1,00** |
| `CalculadoraDeRecompensa:177` | `informado.compareTo(maximo) > 0` | Nenhum teste passa **exatamente 1,50** — e o teto de 1,5× é uma das afirmações mais repetidas do `CLAUDE.md` e do ADR 0022 |

**São quatro casos de teste que faltam, todos de uma linha.** É o achado mais acionável do relatório:
as fronteiras estão certas no código e não estão protegidas por assertion.

### 3.2 O mutante que é equivalente — e por quê

```java
// TransferenciaService:174
boolean origemPrimeiro = carteiraOrigemId.compareTo(carteiraDestinoId) < 0;
```

Sobrevivem aqui **dois** mutantes: negar a condição e mudar a fronteira. E os dois sobrevivem
**corretamente** — não é assertion faltando.

A propriedade que o código garante não é "trava a menor primeiro". É "**existe uma ordem total, e
todas as transações a seguem**", que é o que torna o deadlock A→B / B→A impossível por construção.
Negar `<` para `>=` produz a ordem *decrescente* — outra ordem total, igualmente livre de deadlock.
O comportamento observável é idêntico, então nenhum teste pode distingui-los.

**Este é o tipo de sobrevivente que não se conserta.** Escrever um teste que o mate exigiria afirmar
qual carteira é travada primeiro, o que congelaria um detalhe de implementação em vez da invariante —
e tornaria o teste mais frágil sem tornar o sistema mais correto. Fica registrado como equivalente
conhecido.

(A variante `<= 0` é inalcançável por outro motivo: os dois ids nunca são iguais, porque transferir
para si mesmo é recusado com 422 lá em cima, na linha 57.)

### 3.3 Superfície pública que só o próprio teste usa

```java
// StatusMissao:104 — mutante: retornar sempre Collections.emptySet()
public Set<EventoMissao> eventosPermitidos() {
  return Set.copyOf(TRANSICOES.getOrDefault(this, Map.of()).keySet());
}
```

O mutante sobrevive **apesar dos 148 casos** de `MissaoStateMachineTest`. A causa: nenhum código de
`src/main` chama este método, e o único teste que o chama afirma que ele devolve **vazio**
(`assertThat(StatusMissao.CONCLUIDA.eventosPermitidos()).isEmpty()`) — que é exatamente o que o
mutante faz.

É o padrão que a [varredura de órfãos](../auditoria/varredura-orfaos.md) caçou em 2026-08-20:
superfície pública sem chamador. Aqui o PIT o encontrou por outro caminho, e de forma mais incisiva —
não só "ninguém usa", mas "o teste que existe não distingue a implementação de uma constante".

### 3.4 Os 48 `NO_COVERAGE`, e por que a maioria é aceitável

Quase todos são lambdas de `orElseThrow` — os caminhos "carteira não encontrada", "resgate não
encontrado". São o `RecursoNaoEncontradoException` de estados que os testes de integração não
constroem porque o dado sempre existe. Cobri-los exigiria fabricar id inexistente em cada serviço:
barato, mas de valor baixo, já que o caminho é uma linha e o mesmo padrão está testado noutros
pontos.

**A exceção que vale olhar** é `FinanciamentoCarteiraService.estornarFinanciadores:176`, com um
`MathMutator` (soma → subtração) sem cobertura. O estorno é o mecanismo que impede token preso em
missão morta, e o `CLAUDE.md` dedica dois parágrafos a ele. Vale um teste.

## 4. O que este relatório NÃO diz

- **Score de mutação não é qualidade de teste em escala absoluta.** 70,6% não é comparável a projeto
  nenhum: depende do conjunto de mutadores, do escopo e de quantos equivalentes existem. Serve para
  comparar **este projeto com ele mesmo** ao longo do tempo.
- **Não há gate, e isso é decisão, não omissão.** Nenhum build reprova por causa deste número.
- **Fora dos dois pacotes, nada foi medido.** `compartilhado`, `identidade`, `logistica`,
  `geolocalizacao`, `notificacoes` e `integracoes` não foram mutados — e o custo de incluí-los é
  tempo de build por mutante equivalente em DTO e getter, que foi a razão de restringir.
- **Mutante morto não é teste bom.** PIT confirma que *alguma* assertion falha quando o código muda;
  não diz que a assertion afirma a coisa certa.
- **Os equivalentes não foram enumerados exaustivamente.** A seção 3.2 documenta um; os getters de
  `Lancamento`, `Missao` e `Parceiro` (que respondem pela maior parte dos 97 sobreviventes) são
  quase todos do mesmo tipo, e não foram analisados um a um.
