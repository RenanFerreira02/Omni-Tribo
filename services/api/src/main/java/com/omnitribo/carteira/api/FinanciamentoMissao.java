package com.omnitribo.carteira.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Porta de débito de tokens para financiar uma missão.
 *
 * <p>Só o DÉBITO na carteira do financiador mora aqui. O crédito no pote é feito por {@code
 * missoes} na mesma transação, sobre a linha da missão que ela já travou — a carteira nunca toca a
 * tabela {@code missao}, o que mantém a dependência entre os módulos numa direção só.
 */
public interface FinanciamentoMissao {

  /**
   * Debita tokens do financiador, na transação do chamador.
   *
   * <p>Trava a carteira do financiador com {@code PESSIMISTIC_WRITE}. O chamador já segura o lock
   * da missão: ordem global {@code missao} → {@code carteira}. Financiar e concluir disputam as
   * mesmas duas linhas, e é essa ordem que impede as duas operações de se travarem mutuamente.
   *
   * <p>Saldo insuficiente resulta em {@code RegraNegocioVioladaException} (422) ANTES de qualquer
   * escrita.
   *
   * @return saldo e id do lançamento, com {@code replay = true} se a chave já existia
   */
  ResultadoFinanciamento debitar(
      UUID financiadorId, UUID missaoId, long tokens, String chaveIdempotencia, Instant agora);
}
