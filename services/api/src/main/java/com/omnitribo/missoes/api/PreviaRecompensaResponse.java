package com.omnitribo.missoes.api;

import com.omnitribo.missoes.dominio.CalculadoraDeRecompensa;
import com.omnitribo.missoes.dominio.ComplexidadeMissao;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * Recompensa que a missão teria se fosse criada com estes insumos.
 *
 * <p>Devolve a complexidade EFETIVA — derivada de peso e volume, ou a declarada quando não há
 * nenhum dos dois. Sem ela o app mostraria um número sem conseguir explicá-lo, e o usuário não
 * teria como saber por que uma caixa de porcelanato vale mais que uma torneira.
 *
 * <p>A {@code versaoFormula} vai junto para que o cliente saiba que o valor pode mudar entre
 * calibrações: uma prévia guardada em cache e usada depois de um ajuste estaria desatualizada, e a
 * versão é como se percebe isso.
 */
@Schema(description = "Prévia da recompensa, sem criar missão")
public record PreviaRecompensaResponse(
    int xpRecompensa,
    long tokensRecompensa,
    ComplexidadeMissao complexidade,
    int versaoFormula,
    /**
     * Sempre 1,00 nesta rota, e sair mesmo assim é deliberado.
     *
     * <p>A prévia serve missão criada por usuário, que não passa por avaliação de risco. Expor o
     * neutro deixa explícito no contrato que o multiplicador EXISTE na fórmula e que aqui ele não
     * está agindo — em vez de omiti-lo e fazer o cliente descobrir a diferença ao comparar a prévia
     * com uma missão vinda do webhook.
     */
    BigDecimal multiplicadorRisco) {

  public static PreviaRecompensaResponse de(CalculadoraDeRecompensa.Recompensa r) {
    return new PreviaRecompensaResponse(
        r.xp(), r.tokens(), r.complexidade(), r.versaoFormula(), r.multiplicadorRisco());
  }
}
