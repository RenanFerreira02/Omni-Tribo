package com.omnitribo.logistica.dominio;

/**
 * Uma característica e quanto ela empurrou o log-odds desta entrega.
 *
 * <p>É a unidade da explicabilidade: sem isso o endpoint devolveria um número mágico, e "78% de
 * risco" não seria acionável. Com isso, dá para dizer QUAL condição mudar.
 *
 * @param contribuicao {@code coeficiente × valor CODIFICADO} (z-score para numéricas, 0/1 para
 *     indicadores) — nunca {@code coeficiente × valor bruto}. Somando as contribuições de todas as
 *     características ao intercepto recupera-se exatamente o log-odds, e é essa identidade que
 *     torna a explicação verificável em vez de decorativa.
 * @param pesoRelativo fração de {@code |contribuicao|} sobre a soma dos valores absolutos de todas
 *     as contribuições. <b>É a fração do DESVIO em relação à entrega média, não da
 *     probabilidade.</b> A sigmoide não é linear e uma probabilidade não se decompõe aditivamente:
 *     "a chuva é 30% do risco" é errado; "a chuva responde por 30% do que afasta esta entrega da
 *     média" é certo.
 * @param valorObservado o valor BRUTO em texto ({@code "2 tentativas"}, {@code "18 mm"}), porque é
 *     o que faz a explicação virar português. O z-score não significa nada para quem lê.
 */
public record FatorRisco(
    CaracteristicaRisco caracteristica,
    String rotulo,
    double contribuicao,
    DirecaoDoFator direcao,
    double pesoRelativo,
    String valorObservado) {}
