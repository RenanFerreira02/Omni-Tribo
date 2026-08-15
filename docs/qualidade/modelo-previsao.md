# Modelo de previsão de risco de falha de entrega

**Data:** 2026-08-15
**Artefato avaliado:** `app.logistica.risco`, versão 1
**Como reproduzir:** `bash tools/dataset/gerar.sh`

---

## Antes de qualquer número: o que estes dados são

> **Os dados são SINTÉTICOS. Nós geramos as 5.000 entregas e nós injetamos as correlações que o
> modelo aprendeu.** Não há um único registro de operação real neste treino.
>
> O que este documento demonstra é um **mecanismo funcionando de ponta a ponta** — gerar, treinar,
> medir, publicar, inferir, congelar — e a capacidade de explicar cada previsão. O que ele **não**
> demonstra é que o modelo prevê falhas de entrega no mundo real, e nenhuma métrica abaixo deve ser
> lida como se demonstrasse.
>
> **A validação com dados reais da operação é o próximo passo**, e está registrada como tal no
> ADR 0022. Ela exige o que hoje não existe: um volume de entregas falidas reais com desfecho
> observado. É por isso que `entrega_falida` passou a gravar `risco_probabilidade`, `risco_faixa` e
> `risco_versao_modelo` (V22) — sem previsões registradas, não haveria contra o que comparar quando
> os dados chegarem.

Um modelo honesto sobre dados sintéticos é defensável. Um modelo apresentado como treinado em dados
reais desmonta na primeira pergunta.

---

## As correlações injetadas

Estas são as relações que **nós escrevemos** em `GeradorDatasetEntregas.COEFICIENTE_VERDADEIRO`,
antes de o modelo existir. Cada uma tem uma história operacional plausível — e é a história, não o
número, que sustenta a escolha.

| Sinal | β injetado (log-odds) | Por que é plausível |
|---|---:|---|
| `TENTATIVAS_ANTERIORES` | **+1,10** / tentativa | quem já falhou tende a falhar de novo: rotina do destinatário incompatível com a janela |
| `JANELA_NOITE` (18–21h) | +0,85 | jantar; ninguém atende o interfone, portaria trocou de turno |
| `ENDERECO_RURAL` | +0,70 | acesso difícil, endereçamento impreciso |
| `JANELA_MADRUGADA` (22–05h) | +0,60 | fora de qualquer janela em que alguém receba |
| `ENDERECO_CONDOMINIO` | +0,45 | portaria com regra própria: recusa por horário, por falta de autorização |
| `ENDERECO_COMERCIAL` | +0,30 | em dia útil, comércio recebe bem — efeito isolado pequeno |
| `COMERCIAL_EM_FIM_DE_SEMANA` | **+1,30** | **interação**: comércio fechado no sábado |
| `FIM_DE_SEMANA` | −0,10 | residencial falha *menos*: as pessoas estão em casa |
| `TAXA_HISTORICA_CEP` | +4,00 / unidade | faixa a 30% contra faixa a 5% ⇒ +1,00 no log-odds |
| `CHUVA_MM` | +0,045 / mm | chuva atrasa a rota e desestimula descer para receber |
| `PESO_KG` | +0,025 / kg | volume pesado exige alguém *apto* a receber, não só presente |
| `VOLUME_L` | +0,004 / L | não cabe no armário da portaria |
| `TEMPERATURA_C` | +0,020 / °C | **fraco de propósito** — ver abaixo |
| *(variável OMITIDA)* `motoristaExperiente` | −0,55 | **não é oferecida ao modelo** |

Intercepto verdadeiro: **−3,60**, calibrado para a taxa-base ficar perto de 20%.

**A temperatura é fraca de propósito.** Um conjunto em que toda característica é relevante não é
realista. Ter um preditor que quase não explica nada demonstra que sabemos distinguir sinal de ruído
— e o intervalo largo em que ele é recuperado (+0,034 contra +0,020 injetado) é a própria lição
sobre incerteza amostral.

---

## Como o rótulo nasce: três fontes de erro irredutível

Acurácia perto de 100% seria o sinal mais suspeito possível. Três mecanismos garantem que ela não
aconteça, e cada um imita um fenômeno real:

1. **O rótulo é SORTEADO de `Bernoulli(sigmoide(logOdds))`, nunca decidido por limiar.** Isso cria um
   **erro de Bayes irredutível**: nem o modelo que gerou os dados consegue superá-lo. Medido nesta
   partição de teste: **acurácia máxima teórica = 0,8056**. É a resposta honesta a "por que não 95%?"
   — porque 95% é impossível neste dado.
2. **Variável omitida.** `motoristaExperiente` afeta o desfecho (−0,55) e **não é oferecida ao
   modelo**. Simula o que sempre acontece: parte do que explica a falha não está no sistema.
3. **2% dos rótulos invertidos**, simulando erro de registro no coletor do motorista.

---

## Partição e metodologia

```
5.000 registros  →  3.000 treino (60%)  ·  1.000 validação (20%)  ·  1.000 teste (20%)
```

Estratificada por rótulo, **sem embaralhamento** — as amostras já são i.i.d. por construção, então
fatiar cada estrato na ordem de geração elimina um sorteio aleatório a mais para defender.

**Três partições, não duas, e essa é a decisão metodológica mais importante aqui.**

- **Treino** ajusta μ, σ e os coeficientes.
- **Validação** escolhe o **limiar de decisão** — e só isso.
- **Teste** é tocado **uma única vez**, no fim, para reportar.

Varrer limiares no conjunto de teste e depois reportar o recall *desse mesmo conjunto* é seleção
sobre o conjunto de avaliação: o número sai otimista e deixa de ser estimativa honesta de desempenho
fora da amostra. O limiar é um parâmetro ajustado a partir dos dados, exatamente como qualquer
coeficiente, e precisa da sua própria partição.

**μ e σ da padronização são calculados só na partição de treino.** Calcular sobre o dataset inteiro
vazaria informação do teste para dentro do modelo.

### Treino

| Parâmetro | Valor | Razão |
|---|---|---|
| Algoritmo | gradiente descendente em **lote cheio** | SGD dependeria da ordem de embaralhamento e do estado do RNG |
| Épocas | **2.000, fixas** | sem parada antecipada: um `if` sobre ponto flutuante pararia em épocas diferentes por máquina |
| Taxa de aprendizado | 0,3 | viável porque as numéricas estão padronizadas |
| L2 | λ = 1,0, escalado por 1/n | **nos pesos, nunca no intercepto** — regularizar o intercepto empurraria a taxa-base para 50% |
| Pesos iniciais | zeros | a log-loss é convexa: o mínimo é único |
| Biblioteca | **nenhuma** | ver ADR 0022 |

**`StrictMath`, nunca `Math`.** `Math.exp` só garante erro ≤ 1 ulp e pode usar intrínsecos diferentes
por arquitetura de CPU e versão de JVM; `StrictMath` é especificado bit a bit. Como o treino acumula
~6 milhões de chamadas a `exp` em somas, 1 ulp na primeira época se amplifica. É isso que permite
afirmar "reprodutível" sem ressalva, e é o que `ModeloRiscoTreinoTest` confere com tolerância de
5·10⁻⁷ — meia unidade da última casa publicada, e nada além disso.

**Convergência verificada, não assumida:** norma do gradiente final = **9,3·10⁻⁵**; log-loss = 0,4657.

---

## Métricas

Classe positiva = **"a entrega vai falhar"**. Limiar de decisão publicado: **0,19**.

| Partição | Acurácia | Precisão | Recall | F2 | VP | FP | VN | FN |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Treino | 0,6443 | 0,3708 | 0,7401 | 0,6172 | 521 | 884 | 1412 | 183 |
| Validação | 0,6330 | 0,3507 | 0,6596 | 0,5608 | 155 | 287 | 478 | 80 |
| **Teste** | **0,6440** | **0,3704** | **0,7362** | **0,6148** | **173** | **294** | **471** | **62** |

### Matriz de confusão — partição de teste

|  | Previu **falha** | Previu **sucesso** |
|---|---:|---:|
| **Falhou** | 173 (VP) | 62 (FN) |
| **Deu certo** | 294 (FP) | 471 (VN) |

Taxa-base de falha no dataset: **23,48%**.

### A acurácia é BAIXA, e isso está correto

Um classificador trivial que responde "vai dar certo" para tudo teria **76,5% de acurácia** neste
dado — mais que os 64,4% do modelo — e **recall zero**. Ele não encontraria uma única entrega
problemática, que é exatamente a única coisa que este modelo existe para fazer.

**Acurácia é a métrica errada para dado desbalanceado, e otimizá-la aqui produziria um modelo
inútil.** É por isso que o limiar foi escolhido por recall com piso de precisão, e não por acurácia.

### O modelo carrega informação real

Precisão de 0,3704 contra prevalência de 0,2348 é **lift de 1,58×**: entre as entregas que o modelo
marcou, a proporção de falhas é 58% maior que na população. Recall alto sozinho seria trivial —
bastaria marcar tudo, e a precisão cairia para a prevalência.

---

## Falso positivo × falso negativo: por que otimizamos recall

Os custos são **assimétricos**, e é a ação disparada pelo score que define a assimetria.

**Falso negativo** — prever sucesso numa entrega que vai falhar. A missão nasce com:
- recompensa subvalorizada (multiplicador 1,00 quando deveria ser maior);
- prioridade normal no fan-out, competindo com missões triviais pelo teto de 5 alertas/hora;
- **sem aviso** para quem vai executar.

O executor descobre o problema no local, e a entrega falha de novo. O custo é a segunda falha
inteira: deslocamento perdido, encomenda de volta ao ponto, e um usuário que aceitou uma missão
achando que era simples.

**Falso positivo** — prever falha numa entrega que daria certo. O custo é:
- um prêmio limitado em token (teto de 1,5×);
- uma posição melhor na fila de notificação;
- um aviso na tela que não era necessário.

Nenhum deles causa dano. Por isso o limiar é **0,19**, bem abaixo de 0,50: erramos deliberadamente
para o lado de marcar demais.

### A varredura de limiar (partição de validação)

| Limiar | Acurácia | Precisão | Recall | F2 |
|---:|---:|---:|---:|---:|
| 0,05 | 0,2460 | 0,2371 | 0,9957 | 0,6072 |
| 0,10 | 0,4040 | 0,2718 | 0,9149 | 0,6210 |
| 0,15 | 0,5580 | 0,3181 | 0,7702 | 0,5997 |
| **0,19** | **0,6330** | **0,3507** | **0,6596** | **0,5608** ← **escolhido** |
| 0,20 | 0,6500 | 0,3614 | 0,6383 | 0,5535 |
| 0,25 | 0,7040 | 0,4038 | 0,5447 | 0,5091 |
| 0,30 | 0,7430 | 0,4545 | 0,4681 | 0,4653 |
| 0,35 | 0,7640 | 0,4975 | 0,4213 | 0,4346 |
| 0,40 | 0,7710 | 0,5190 | 0,3489 | 0,3734 |
| 0,50 | 0,7860 | 0,5981 | 0,2723 | 0,3056 |
| 0,60 | 0,7850 | 0,6724 | 0,1660 | 0,1954 |
| 0,70 | 0,7760 | 0,7037 | 0,0809 | 0,0982 |
| 0,85 | 0,7700 | 1,0000 | 0,0213 | 0,0265 |
| 0,95 | 0,7650 | 0,0000 | 0,0000 | 0,0000 |

_(amostrada de 5 em 5 pontos; a grade completa tem 99 limiares e é reproduzível por
`bash tools/dataset/gerar.sh`)_

**O 0,19 é o menor limiar que ainda sustenta o piso.** Em 0,15 a precisão cai para 0,3181 — abaixo
de 0,35 — e o candidato é descartado apesar do recall de 0,77. É o piso agindo exatamente como
projetado: ele impede que a busca por recall arraste o modelo até "marque tudo", que é o que as
linhas de 0,05 e 0,10 mostram (recall de 0,99 com precisão de 0,24, ou seja, quase a prevalência).

**As duas últimas linhas são o extremo oposto e valem ler.** Em 0,85 a precisão vira 1,00 — todo
alerta acerta — mas o recall é 0,02: o modelo encontra 2% das entregas problemáticas. Em 0,95 não
marca nenhuma, e a "acurácia" de 0,765 é exatamente a do classificador trivial. Um modelo otimizado
para precisão ou para acurácia converge para a inutilidade.

**Regra de escolha:** maior recall sujeito a **precisão ≥ 0,35**. Desempate por maior precisão,
depois por maior limiar — determinístico, sem ambiguidade.

**Por que piso de precisão e não "maximize F2".** O piso é uma afirmação de produto que se defende em
uma frase: abaixo dele, mais de dois terços dos alertas são falsos e o executor para de ler — momento
em que o modelo inteiro perde valor, por melhor que seja o recall. O β=2 do F2 não tem história
equivalente; ele é reportado como corroboração, não como critério.

**0,35 e não 0,50** porque nenhuma das três ações disparadas causa dano quando errada. Se marcar uma
entrega acionasse uma visita técnica, meia dúzia de falsos positivos por acerto seria caro demais e o
piso teria de ser outro.

---

## O modelo aprendeu o mecanismo, ou decorou o dado?

Como conhecemos os coeficientes verdadeiros, dá para responder isso **com uma tabela** em vez de
retórica. `β_bruto = β_padronizado / σ`.

| Característica | β injetado | β recuperado | razão | veredito |
|---|---:|---:|---:|---|
| `TAXA_HISTORICA_CEP` | +4,0000 | +4,0873 | 1,02 | ✅ recuperado |
| `ENDERECO_RURAL` | +0,7000 | +0,6458 | 0,92 | ✅ recuperado |
| `TENTATIVAS_ANTERIORES` | +1,1000 | +0,9519 | 0,87 | ✅ recuperado |
| `CHUVA_MM` | +0,0450 | +0,0345 | 0,77 | ✅ recuperado |
| `JANELA_NOITE` | +0,8500 | +0,6458 | 0,76 | ✅ recuperado |
| `COMERCIAL_EM_FIM_DE_SEMANA` | +1,3000 | +0,9774 | 0,75 | ✅ recuperado |
| `JANELA_TARDE` | +0,2500 | +0,3133 | 1,25 | 🟡 sinal certo, magnitude imprecisa |
| `ENDERECO_CONDOMINIO` | +0,4500 | +0,5930 | 1,32 | 🟡 sinal certo, magnitude imprecisa |
| `ENDERECO_COMERCIAL` | +0,3000 | +0,4491 | 1,50 | 🟡 sinal certo, magnitude imprecisa |
| `TEMPERATURA_C` | +0,0200 | +0,0343 | 1,72 | 🟡 efeito fraco por construção |
| `PESO_KG` | +0,0250 | +0,0445 | 1,78 | ❌ colinear com volume |
| `VOLUME_L` | +0,0040 | +0,0000 | 0,01 | ❌ colinear com peso |
| `JANELA_MADRUGADA` | +0,6000 | +0,2309 | 0,38 | ❌ poucas amostras |
| `FIM_DE_SEMANA` | −0,1000 | −0,3040 | 3,04 | ❌ efeito pequeno demais |

**Os seis efeitos fortes e bem identificados são recuperados dentro de 30%**, e é isso que
`ModeloRiscoTreinoTest.os_coeficientes_recuperados_batem_com_os_injetados_nos_efeitos_fortes` trava.
Os demais **não são recuperáveis neste tamanho de amostra**, e essas falhas são informativas:

- **`PESO_KG` e `VOLUME_L` são colineares por construção** (volume é gerado a partir do peso, com
  variação própria). O modelo atribuiu quase todo o efeito conjunto ao peso e ~0 ao volume. Isto é
  **multicolinearidade** em livro-texto: a soma dos efeitos é aproximadamente capturada, a atribuição
  individual não. Nenhuma regularização resolve — é uma limitação de identificabilidade, não de
  ajuste.
- **`JANELA_MADRUGADA`** cobre 8 horas com peso de sorteio baixo: poucas amostras, estimativa ruidosa.
- **`FIM_DE_SEMANA`** tem efeito verdadeiro de −0,10, pequeno demais para ser separado do ruído com
  3.000 amostras. O sinal (negativo) está certo; a magnitude, não.

Preferimos publicar estas quatro linhas vermelhas a afrouxar a tolerância até tudo passar.

### A interação foi aprendida

`COMERCIAL_EM_FIM_DE_SEMANA` recuperado em +0,98 contra +1,30 injetado, e — mais importante — maior
que a soma dos efeitos isolados de `ENDERECO_COMERCIAL` (+0,45) e `FIM_DE_SEMANA` (−0,30).

**Regressão logística é aditiva no log-odds e não descobre interação sozinha.** O termo de produto
teve de ser oferecido explicitamente ao modelo. Isso é uma **limitação medida do modelo linear**, não
um detalhe: uma árvore de decisão encontraria a interação por conta própria. A escolha pelo linear e
a alternativa descartada estão no ADR 0022.

---

## Explicabilidade

Cada previsão devolve os três fatores de maior contribuição:

```
contribuicao_j = β_j × z_j          (z-score para numéricas, 0/1 para indicadores)
logOdds        = intercepto + Σ contribuicao_j
```

A identidade é exata e **verificada por teste**
(`ModeloRiscoTreinoTest.a_soma_das_contribuicoes_reconstroi_o_log_odds`, tolerância 10⁻¹²). É ela que
torna a explicação auditável em vez de decorativa: dá para recalcular o score a partir dos fatores
exibidos.

**O intercepto fica fora do ranking.** Ele não é um fator *desta* entrega — é o log-odds de uma
entrega média, num endereço residencial, numa manhã de dia útil. Incluí-lo faria o intercepto,
tipicamente o maior valor absoluto, aparecer como "principal fator de risco" em toda previsão.

**`pesoRelativo` é a fração do *desvio em relação à entrega média*, não da probabilidade.** A sigmoide
não é linear e probabilidade não se decompõe aditivamente. "A chuva é 30% do risco" é errado; "a
chuva responde por 30% do que afasta esta entrega da média" é certo.

### Exemplo

```
POST /api/v1/logistica/previsao-falha
{ "janelaHoraInicio": 19, "diaSemana": "SATURDAY", "tipoEndereco": "COMERCIAL",
  "cep": "08010000", "pesoKg": 25.00, "volumeL": 200.00,
  "tentativasAnteriores": 2, "chuvaMm": 18.0, "temperaturaC": 17.0 }
```

devolve probabilidade na faixa ALTO, multiplicador dentro de [1,00; 1,50], e os três termos
dominantes — `TENTATIVAS_ANTERIORES`, `TAXA_HISTORICA_CEP` e `COMERCIAL_EM_FIM_DE_SEMANA` — cada um
com contribuição, direção e o valor bruto observado em português.

---

## O que esta fase NÃO garante

- **Não há validação com dados reais.** Nenhuma métrica acima diz respeito à operação. É o próximo
  passo, e o schema já está preparado para ele (V22).
- **O modelo não descobre interações sozinho.** Só a que foi explicitamente oferecida.
- **`PESO_KG` e `VOLUME_L` não são individualmente interpretáveis** — ver acima.
- **Não há detecção de deriva.** Se a operação mudar, nada avisa que os coeficientes envelheceram; o
  que existe é `versao_modelo` gravado em cada previsão, para que a comparação seja possível depois.
- **O limiar foi escolhido para dados sintéticos.** Com dados reais, a prevalência muda e o limiar
  precisa ser reescolhido — não é constante universal.
- **O modelo é retrospectivo por construção neste uso.** No caminho do webhook, ele pontua uma
  entrega que **já falhou**; o score descreve o perfil de dificuldade daquele contexto, e é isso que
  justifica pagar mais e priorizar. O uso preditivo genuíno é o endpoint `/previsao-falha`, chamado
  *antes* do despacho.

---

## Reprodução

```bash
bash tools/dataset/gerar.sh
```

Regenera o dataset com a semente `20260814`, re-treina, e escreve CSV, bloco YAML e tabelas em
`tools/dataset/`. Determinístico: o digest SHA-256 do dataset é
`e812d57558fd517bc4956abbe3c44d40c9a3f8bb1cfea13f7269ddf2cfda02e2` e está travado em
`DatasetSinteticoTest`.

O `./mvnw verify` **re-treina o modelo do zero a cada build** e confere que os coeficientes
publicados em `application.yml` são exatamente os que o treino produz. Editar um coeficiente à mão
quebra o build — de propósito.
