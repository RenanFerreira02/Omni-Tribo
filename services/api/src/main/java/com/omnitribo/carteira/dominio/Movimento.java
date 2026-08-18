package com.omnitribo.carteira.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Uma mutação de saldo a ser registrada no ledger por {@link LivroRazaoService}.
 *
 * <p>Carrega o valor em VALOR ABSOLUTO; a direção mora em {@code sinal}. Nunca passe valor negativo
 * para representar débito — {@code ck_lancamento_valores_nao_negativos} (V13) rejeita, e por um bom
 * motivo: a soma do ledger é {@code SUM(CASE sinal WHEN 'CREDITO' THEN v ELSE -v END)}, então um
 * valor negativo numa linha DEBITO contaria como crédito e a reconciliação passaria a mentir sem
 * produzir erro nenhum.
 *
 * @param valorBrl dinheiro movido, sempre {@code >= 0}; use {@link BigDecimal#ZERO} se a operação é
 *     só de tokens
 * @param valorTokens tokens movidos, sempre {@code >= 0}; zero se a operação é só de BRL
 * @param missaoId missão relacionada, ou {@code null}. UUID puro, sem FK — fronteira
 *     carteira→missoes
 * @param contraparteCarteiraId a outra ponta numa transferência, ou {@code null}
 * @param chaveIdempotencia já derivada por {@code ChaveIdempotencia}; nunca a chave crua do cliente
 * @param mensagem texto opcional da transferência P2P
 * @param agora instante da operação, recebido e não lido aqui — a unidade de trabalho inteira
 *     compartilha um relógio só
 */
public record Movimento(
    SinalLancamento sinal,
    MotivoLancamento motivo,
    BigDecimal valorBrl,
    long valorTokens,
    UUID missaoId,
    UUID contraparteCarteiraId,
    String chaveIdempotencia,
    String mensagem,
    Instant agora) {

  public Movimento {
    if (valorBrl == null || valorBrl.signum() < 0) {
      throw new IllegalArgumentException("valorBrl não pode ser nulo nem negativo.");
    }
    if (valorTokens < 0) {
      throw new IllegalArgumentException("valorTokens não pode ser negativo.");
    }
    // Espelha ck_lancamento_valor_nao_nulo: um lançamento de zero em ambas as moedas consumiria
    // uma chave de idempotência sem mover nada, e o cliente leria isso como sucesso.
    if (valorBrl.signum() == 0 && valorTokens == 0) {
      throw new IllegalArgumentException("Movimento precisa mover BRL ou tokens.");
    }
  }

  /** Movimento só de tokens — transferência, financiamento, recompensa de missão TRIBO/COLETA. */
  public static Movimento deTokens(
      SinalLancamento sinal,
      MotivoLancamento motivo,
      long tokens,
      UUID missaoId,
      UUID contraparteCarteiraId,
      String chaveIdempotencia,
      String mensagem,
      Instant agora) {
    return new Movimento(
        sinal,
        motivo,
        BigDecimal.ZERO,
        tokens,
        missaoId,
        contraparteCarteiraId,
        chaveIdempotencia,
        mensagem,
        agora);
  }
}
