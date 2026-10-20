package com.omnitribo.carteira.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de débito de tokens para financiar uma missão. O crédito no pote é feito por {@code
 * missoes}, na mesma transação — a carteira nunca toca a tabela {@code missao}.
 */
public interface FinanciamentoMissao {

  /**
   * Trava a carteira do financiador e sonda a idempotência, SEM escrever — para a ordem canônica
   * (locks → sonda → valida → escreve): sondar depois das validações fazia o retry de um
   * financiamento bem-sucedido receber 422.
   *
   * @return o resultado anterior quando a chave já existe; vazio na primeira vez
   */
  Optional<ResultadoFinanciamento> sondar(UUID financiadorId, String chaveIdempotencia);

  /**
   * Debita o financiador, na transação do chamador, que já segura o lock da missão (ordem missao →
   * carteira). Saldo insuficiente é 422 antes de qualquer escrita.
   *
   * @return saldo e id do lançamento, com {@code replay = true} se a chave já existia
   */
  ResultadoFinanciamento debitar(
      UUID financiadorId, UUID missaoId, long tokens, String chaveIdempotencia, Instant agora);

  /**
   * Debita o PATROCINADOR para o pote de uma missão de retirada ({@code
   * FINANCIAMENTO_PATROCINADOR}).
   *
   * <p>Saldo insuficiente devolve VAZIO, não lança: a falta de patrocínio é desfecho de negócio
   * (SEM_PATROCINIO) que precisa ser GRAVADO na entrega falida. Ordem de lock {@code ponto_custodia
   * → carteira}: a missão ainda não existe no banco (ADR 0024 §7). Todo webhook da mesma
   * transportadora serializa nesta carteira — aceito.
   *
   * @param tokens positivo; recompensa zero não chama este método
   * @return o resultado do débito, ou vazio quando o saldo não cobre
   */
  Optional<ResultadoFinanciamento> debitarPatrocinador(
      UUID patrocinadorUsuarioId,
      UUID missaoId,
      long tokens,
      String chaveIdempotencia,
      Instant agora);
}
