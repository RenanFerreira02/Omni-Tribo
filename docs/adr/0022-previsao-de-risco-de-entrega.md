# 0022 — Previsão de risco de entrega: regressão logística em Java puro, treinada no build

**Data:** 2026-08-15
**Status:** Aceito

---

## Contexto

O webhook de transportadora converte toda entrega falida em missão de retirada tratando todas como
iguais: a recompensa sai de peso, volume, distância e valor ofertado, e o fan-out despacha na ordem
em que `filtrarPorNivelMinimo` devolveu. Nada distingue um endereço que falha sempre de um que falhou
uma vez. Quem executa não sabe o que está aceitando, e o sistema não prioriza o caso difícil.

Um modelo que estime a probabilidade de falha resolveria três coisas de uma vez: reprecificar a
missão, priorizar a notificação, e avisar quem vai executar. Mas o contexto do projeto impõe
restrições que eliminam a maior parte das abordagens usuais:

- **É trabalho acadêmico a ser defendido oralmente.** Explicar *por que* o modelo previu 78% vale
  mais que meio ponto de acurácia. Um modelo que acerta mais e não se explica é pior aqui.
- **Não existem dados reais.** Nenhuma entrega falida da operação, porque não há operação.
- **O `verify` é a barreira de qualidade do projeto.** Tudo que o projeto garante, garante por teste
  executado no build.
- **O runtime não pode ganhar um serviço novo.** O escopo declarado corta broker, Redis, proxy e
  observabilidade externa; um processo Python seria a mesma classe de adição.

---

## Decisão

**Adotamos regressão logística binária, treinada em Java puro DENTRO do `./mvnw verify`, sobre um
dataset sintético gerado por código, com os coeficientes publicados como configuração e a inferência
em runtime lendo essa configuração.**

Cinco decisões que se sustentam mutuamente:

### 1. Java puro, sem biblioteca de ML, treino e inferência

O `pom.xml` não ganha nenhuma dependência. Gerador, treinador e avaliador vivem em `src/test`
(`com.omnitribo.logistica.treino`); só o inferidor (`PrevisorDeRisco`) vive em `src/main`.

O gradiente descendente para regressão logística são ~40 linhas. O que ele nos dá que uma biblioteca
não daria: **o pipeline inteiro roda no `verify`, em menos de 3 segundos, em qualquer máquina que já
compile o projeto** — sem `pip install`, sem ambiente virtual, sem versão de wheel para conferir.

### 2. O dataset é sintético, e isso vai declarado em todo lugar

Em `docs/qualidade/modelo-previsao.md`, no ADR, no javadoc de `PrevisorDeRisco`, no comentário do
`application.yml` e na descrição OpenAPI do endpoint. As correlações injetadas estão listadas uma a
uma, com o valor exato de cada coeficiente verdadeiro.

**Validação com dados reais da operação é o próximo passo declarado.** A V22 já grava
`risco_probabilidade`, `risco_faixa` e `risco_versao_modelo` em `entrega_falida` — sem previsões
registradas, não haveria contra o que comparar quando os dados existirem.

### 3. O rótulo é sorteado, não decidido

`y ~ Bernoulli(sigmoide(logOdds))`, mais uma variável omitida (`motoristaExperiente`, −0,55, não
oferecida ao modelo) e 2% de rótulos invertidos. Isso cria um **erro de Bayes irredutível** de 0,8056
medido na partição de teste: nem o modelo que gerou os dados o supera.

Um dataset em que o rótulo é função determinística das características produziria acurácia perto de
100% — o sinal mais suspeito possível numa avaliação. `ModeloRiscoTreinoTest` assere acurácia
**entre 0,55 e 0,90**, com teto: um resultado alto demais reprova, em vez de comemorar.

### 4. Três partições, e o limiar é escolhido na validação

60/20/20. Treino ajusta coeficientes e μ/σ; **validação escolhe o limiar**; teste é tocado uma vez.

**Isto contraria a especificação recebida**, que pedia separação treino/teste e otimização de recall
sem mencionar a terceira partição. Varrer 99 limiares no conjunto de teste e depois reportar o recall
*desse mesmo conjunto* é seleção sobre o conjunto de avaliação: o recall publicado sairia otimista e
deixaria de ser estimativa honesta. O limiar é um parâmetro ajustado a partir dos dados, exatamente
como um coeficiente, e precisa da sua própria partição.

Regra: **maior recall sujeito a precisão ≥ 0,35**. Resultado: limiar **0,19**, recall 0,7362 no teste.

### 5. O multiplicador é congelado, e o teto é estreito

O score vira multiplicador linear da recompensa em TOKEN, limitado a **[1,00; 1,50]**, congelado em
`missao.multiplicador_risco` junto com `versao_formula` — a coluna existe reservada desde a V16
exatamente para isto. `app.missoes.recompensa.versao` sobe para **3**.

**O teto é estreito por causa da Pendência #1.** Missões de ENTREGA ainda CUNHAM token — não pagam de
pote — porque o financiador correto delas é o patrocinador, que não existe. Sem teto, o multiplicador
multiplicaria essa cunhagem pelo risco. Com ele, a ampliação é limitada, conhecida e documentada. O
teto vive em DOIS blocos de configuração (`logistica.risco` e `missoes.recompensa`) de propósito:
recalibrar o modelo não deve conseguir, sozinho, ampliar a emissão de token. `CoerenciaTetoRiscoTest`
falha se divergirem.

### 6. O clima é buscado FORA da transação do webhook

`WebhookTransportadoraController` consulta `ConsultaClima` (porta nova em `integracoes/api`) **antes**
de chamar `EntregaFalidaService.registrar`, que é `@Transactional` e adquire `SELECT ... FOR UPDATE`
no ponto de custódia como primeira leitura.

Consultar um provedor externo dentro daquela transação seguraria o lock durante uma chamada de rede,
e sob rajada de webhooks no mesmo ponto as transações enfileirariam atrás de I/O externo. É o mesmo
desenho que derrubou o check-in da F6 e levou o projeto a proibir `REQUIRES_NEW` no caminho de valor.

A porta devolve `Optional` e **nunca lança**: provedor fora do ar, tempo esgotado ou recusa do
bulkhead produzem imputação pela média do treino (z-score 0, contribuição nula), registrada em
`featuresImputadas`. Recusar a entrega falida porque o Open-Meteo caiu faria a transportadora
reenviar em laço enquanto a encomenda continua no ponto sem missão.

---

## Consequências

**Positivas:**

- **Reprodutibilidade demonstrável, não afirmada.** `ModeloRiscoTreinoTest` re-treina do zero a cada
  `verify` e confere cada coeficiente contra o `application.yml`, com tolerância de 5·10⁻⁷ — meia
  unidade da última casa publicada. Editar um coeficiente à mão quebra o build.
- **A inferência de runtime é comparada com a do treinador** em toda a partição de teste. É o que pega
  o defeito que igualdade de coeficiente não pegaria: encoder do treino divergindo do de produção.
- **Explicação auditável.** `logOdds = intercepto + Σ contribuições` é identidade exata, verificada
  por teste com tolerância 10⁻¹². Dá para recalcular o score a partir dos fatores exibidos.
- **Um comando reproduz tudo:** `bash tools/dataset/gerar.sh`.
- **Zero dependência nova**, zero migration de dados, zero processo no runtime.

**Negativas / trade-offs:**

- **Implementamos o treinador, então precisamos prová-lo correto.** O custo foi
  `CoeficientesRecuperadosTest` comparando estimados com injetados, mais asserção de convergência
  pela norma do gradiente. Uma biblioteca consagrada dispensaria esse ônus.
- **`StrictMath` obrigatório em toda a aritmética do modelo.** `Math.exp` admite 1 ulp de variação
  entre arquiteturas de CPU e versões de JVM, e o treino acumula ~6 milhões de chamadas. Trocar por
  `Math` é regressão silenciosa que só aparece em outra máquina.
- **O modelo linear não descobre interações.** `COMERCIAL_EM_FIM_DE_SEMANA` precisou ser oferecido
  como termo de produto explícito. Uma árvore encontraria sozinha.
- **`PESO_KG` e `VOLUME_L` não são individualmente identificáveis** — são colineares por construção,
  e o modelo atribui o efeito conjunto a um deles. Documentado como achado, não escondido.
- **Acurácia (0,644) é MENOR que a do classificador trivial** (0,765, que responde "vai dar certo"
  para tudo e tem recall zero). É a consequência correta de otimizar recall em dado desbalanceado, e
  exige explicação toda vez que alguém olha o número isolado.
- **A cunhagem de token cresce**, limitada ao teto. Amplia a Pendência #1 de forma medida.
- **Mais superfície para defender oralmente**: modelo e economia mudam na mesma entrega.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| **Treinar em Python/scikit-learn, notebook em `tools/`, exportar coeficientes** | Era viável e foi seriamente considerada — o Fedora 44 empacota `python3-scikit-learn 1.8.0-rc1`. Descartada porque o Python desta máquina (3.14.6) **não tem sequer `pip`**, e nem numpy, pandas ou jupyter: o caminho exigiria `sudo dnf install` de quatro pacotes. Isso cria um ambiente que o `./mvnw verify` **não reproduz** — o CI não re-treinaria, os coeficientes virariam artefato que ninguém confere, e a pergunta "estes números vieram deste dataset?" ficaria sem resposta executável. O ganho seria métricas vindas de biblioteca consagrada; o custo é a garantia central desta fase. |
| **Serviço Python no runtime (FastAPI + sklearn)** | Proibido pelo escopo, e sem benefício: a inferência de uma regressão logística são 14 multiplicações e uma exponencial. Acrescentaria um processo, uma porta, um contrato HTTP e um modo de falha — para calcular uma soma ponderada. |
| **Árvore de decisão rasa** | Encontraria a interação `comercial × fim de semana` sozinha, o que é uma vantagem real. Descartada porque a explicação de árvore é um CAMINHO ("porque tentativas > 1 e endereço = comercial"), enquanto a de regressão é uma DECOMPOSIÇÃO aditiva com peso por fator. `fatoresPrincipais` com contribuição numérica por característica é mais acionável na tela e mais fácil de defender, e a identidade `Σ contribuições = logOdds` não tem equivalente em árvore. |
| **Ponderar a classe positiva na log-loss (peso 3× nos positivos)** | Funciona e é comum, mas **descalibra a probabilidade**: um "72% de risco" exibido ao usuário passaria a valer 45% de verdade. Como a probabilidade aparece na tela e as faixas derivam dela, mentir nesse número é pior que perder recall. A resposta correta de teoria da decisão é: o modelo estima P(falha) sem viés, e o custo assimétrico entra na REGRA DE DECISÃO — ou seja, no limiar. |
| **Duas partições (treino/teste), varrendo o limiar no teste** | É o que a especificação recebida sugeria. Produz recall publicado otimista, porque o limiar foi escolhido olhando o mesmo conjunto que reporta a métrica. Custou uma linha de código corrigir e é o tipo de detalhe que uma banca atenta cobra. |
| **Tabela de taxa histórica por CEP em banco, alimentada por `entrega_falida`** | Separaria o parâmetro do modelo que o produziu: a taxa mudaria a cada nova entrega registrada, e a pergunta "esta taxa estava vigente quando este score foi calculado?" ficaria sem resposta. Com dados reais isso passa a ser o desenho certo; com dataset sintético, a tabela seria alimentada por nada. Hoje vive em `app.logistica.risco.taxa-por-faixa-cep`, congelada com os coeficientes. |
| **Chamar o clima dentro da transação do webhook** | Seguraria `FOR UPDATE` do ponto de custódia durante uma chamada de rede. Exatamente o desenho que derrubou o check-in da F6. |
| **Recusar o webhook (5xx) quando o clima está indisponível** | Faria a transportadora reenviar em laço enquanto a encomenda continua no ponto sem missão — o mesmo raciocínio que faz ponto lotado responder 200 com desfecho RECUSADA (ADR 0021). Clima ausente degrada o score; não invalida o fato. |
| **Multiplicador de risco sem teto** | Maior efeito na demonstração, mas ENTREGA ainda cunha token: a emissão cresceria proporcionalmente ao risco, sem financiador. Preferimos a ampliação limitada e documentada. |
| **Deixar o congelamento do multiplicador para uma entrega seguinte** | Reduziria a superfície de defesa oral (modelo e economia separados). Descartada porque o congelamento é o item 5 da especificação e a coluna `missao.multiplicador_risco` está reservada desde a V16 esperando por ele. |

---

## Referências

- `docs/qualidade/modelo-previsao.md` — métricas, matriz de confusão, correlações injetadas, e o que
  esta fase não garante. **É o documento a defender oralmente.**
- ADR 0009 — economia do cuidado: por que a recompensa é TOKEN e por que quem cria não paga.
- ADR 0011 — dependências externas: por que falha de provedor responde 503 e a UI esconde o recurso.
  Este ADR registra a EXCEÇÃO a essa regra no caminho do webhook.
- ADR 0018 — fronteira de `compartilhado` e por que portas entre módulos usam só tipos JDK.
- ADR 0021 — verificação de webhook de transportadora.
- CLAUDE.md, Pendência #1 — por que ENTREGA ainda cunha token.

---

## Retificação — 2026-08-20 (ADR 0024)

**A premissa da seção "O teto é estreito por causa da Pendência #1" caducou.** Quando este ADR foi
escrito, missões de ENTREGA CUNHAVAM token na conclusão, então o multiplicador de risco multiplicava
emissão sem financiador — e o teto de 1,5× foi escolhido estreito por causa disso.

Desde a V23 a missão de retirada nasce com o pote financiado pelo PATROCINADOR
(`missao.fonte_pote = 'PATROCINADOR'`), e a cunhagem saiu do fim do ciclo: o único ponto de emissão
passou a ser `APORTE_PATROCINADOR`, por endpoint ADMIN. O multiplicador **não amplia mais emissão
nenhuma** — ele aumenta quanto o patrocinador paga do próprio saldo.

O que NÃO mudou: o teto continua em 1,5×, continua congelado em `missao.multiplicador_risco` e
continua travado por `CoerenciaTetoRiscoTest`. **Reavaliá-lo virou possível, não obrigatório** — e
seria trabalho com o mesmo problema de sempre, os dados serem sintéticos. A referência à Pendência #1
no rodapé deste ADR aponta para uma pendência que não existe mais; o raciocínio dela está aqui.
