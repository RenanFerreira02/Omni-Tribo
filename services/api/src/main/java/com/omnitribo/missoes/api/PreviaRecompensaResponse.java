package com.omnitribo.missoes.api;

import com.omnitribo.missoes.dominio.CalculadoraDeRecompensa;
import com.omnitribo.missoes.dominio.ComplexidadeMissao;
import io.swagger.v3.oas.annotations.media.Schema;

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
    int xpRecompensa, long tokensRecompensa, ComplexidadeMissao complexidade, int versaoFormula) {

  public static PreviaRecompensaResponse de(CalculadoraDeRecompensa.Recompensa r) {
    return new PreviaRecompensaResponse(r.xp(), r.tokens(), r.complexidade(), r.versaoFormula());
  }
}
