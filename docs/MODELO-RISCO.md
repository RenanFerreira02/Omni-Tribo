# Modelo de risco de falha de entrega (AI Logistics Extension)

> **Os dados são SINTÉTICOS.** Geramos 5.000 entregas e injetamos as correlações. Nenhuma métrica
> abaixo diz respeito a operação real; validar com dado real é o próximo passo.

## O que ele decide

No instante em que a transportadora reporta a falha, o modelo estima a chance de **a próxima
tentativa também falhar**. Essa estimativa vira três coisas:

1. **Quanto a missão paga.** `multiplicador = 1,00 + 0,50 × probabilidade`, limitado a
   **[1,00; 1,50]**. Ele entra na **base** da recompensa, junto da complexidade, e nunca sobre o
   total: sobre o total ele escalaria também distância, peso e volume. Fica congelado na missão.
2. **A prioridade do alerta.** Risco ALTO fura o teto de alertas por hora.
3. **Um aviso acionável** no detalhe da missão, por exemplo: *"combine o horário com o destinatário
   antes de ir"*.

Missão criada por pessoa recebe 1,00×; só a entrega falida é avaliada.

## Como

Regressão logística binária em **Java puro, sem biblioteca de ML**. Gerador, treinador e avaliador
ficam em `src/test/.../logistica/treino` e rodam **dentro do `./mvnw verify`**. Os coeficientes são
publicados em `app.logistica.risco` (`application.yml`), e editar um deles à mão quebra o build. A
inferência em runtime é `PrevisorDeRisco` (`logistica/dominio`), que lê essa configuração.
`tools/dataset/gerar.sh` regenera o CSV e o relatório.

Faixas: **ALTO ≥ 0,19** (o limiar de decisão), **MÉDIO ≥ 0,10**, BAIXO abaixo disso. O limiar fica
bem abaixo de 0,5 porque o erro é assimétrico: um falso negativo é uma entrega difícil sem aviso.

## As 14 features (β publicados)

| Feature | β | Leitura |
|---|---:|---|
| `COMERCIAL_EM_FIM_DE_SEMANA` | +0,977 | comércio fechado no sábado (interação) |
| `TENTATIVAS_ANTERIORES` | +0,696 | quem já falhou tende a falhar de novo |
| `ENDERECO_RURAL` | +0,646 | acesso difícil |
| `JANELA_NOITE` (18–21 h) | +0,646 | interfone sem resposta, troca de turno da portaria |
| `ENDERECO_CONDOMINIO` | +0,593 | portaria com regra própria |
| `ENDERECO_COMERCIAL` | +0,449 | isolado é pequeno: em dia útil o comércio recebe |
| `TAXA_HISTORICA_CEP` | +0,351 | histórico da faixa de CEP |
| `JANELA_TARDE` (12–17 h) | +0,313 | |
| `JANELA_MADRUGADA` | +0,231 | |
| `TEMPERATURA_C` | +0,158 | clima consultado ao vivo |
| `PESO_KG` | +0,152 | carga pesada exige alguém apto a receber |
| `CHUVA_MM` | +0,145 | clima consultado ao vivo |
| `VOLUME_L` | +0,001 | praticamente nulo |
| `FIM_DE_SEMANA` | −0,304 | residencial falha menos: as pessoas estão em casa |

## Métricas (partição de teste, 1.000 registros; partição 60/20/20)

| Acurácia | Precisão | Recall | F2 | Brier | Brier do chute constante | Erro de calibração |
|---:|---:|---:|---:|---:|---:|---:|
| 0,644 | 0,370 | 0,736 | 0,615 | **0,1485** | 0,1798 | 0,0179 |

- **"A acurácia é menor que a de um chute?"** É, e isso está certo. Responder "vai dar certo" para
  tudo dá 76,5% de acurácia com **recall zero**. Acurácia é a métrica errada para dado
  desbalanceado (a taxa-base de falha é 23,5%). O **Brier é 17,4% melhor** que o do chute, e a
  precisão de 0,37 contra a prevalência de 0,235 é um **lift de 1,58×**.
- **Calibração.** A frequência observada cresce de 9,5% para 55,5% da primeira à última faixa de
  probabilidade, com erro médio de 0,018: o que a tela promete é o que acontece.
- **O teto teórico de acurácia é 0,806**, porque o rótulo é sorteado de
  `Bernoulli(sigmoide(logOdds))`. Acurácia perto de 100% seria o sinal mais suspeito possível.
- O limiar é escolhido na partição de **validação** (recall com piso de precisão), e a
  padronização usa μ/σ só do treino.

`POST /api/v1/logistica/previsao-falha` devolve a probabilidade, a faixa e os `fatoresPrincipais`.
É POST sem escrita para não pôr CEP e peso em query string.
