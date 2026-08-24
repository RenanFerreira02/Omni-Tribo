package com.omnitribo.carteira.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Emissão de token na carteira de um patrocinador. O ÚNICO ponto de cunhagem do sistema.
 *
 * <p>Até a V23 a cunhagem estava espalhada e implícita: toda missão ENTREGA ou AJUDA criava tokens
 * na conclusão, sem financiador e sem registro de que uma emissão tinha acontecido. A reconciliação
 * não a via, porque ledger e projeção continuavam batendo — a invariante violada era a CONSERVAÇÃO,
 * que é outra. Esta porta não elimina a cunhagem; ela a concentra num evento explícito, com ator
 * ADMIN, trilha de auditoria e chave de idempotência.
 *
 * <p>Depois disto vale a afirmação forte: {@code SUM(carteira.saldo_tokens) +
 * SUM(missao.pote_tokens)} é constante em TODO o ciclo de missões, para as quatro categorias, e só
 * um aporte a altera.
 *
 * <p><b>Não confundir com {@code MotivoLancamento.BONUS}</b>, que continua sem call site: bônus
 * cunharia sem sumidouro correspondente. O token aportado tem destino — vai para o pote da missão e
 * do pote para o executor.
 */
public interface AporteToken {

  /**
   * Credita tokens na carteira do patrocinador, na transação do chamador.
   *
   * <p>{@code MANDATORY} como as demais portas de carteira: chamar isto fora de uma transação é
   * erro de programação, e com {@code REQUIRED} o método abriria transação própria e o lock que dá
   * sentido à sondagem não existiria.
   *
   * <p>Ordem canônica: trava a carteira, sonda a chave, e só então escreve. A idempotência aqui não
   * é conforto de cliente — é o que impede um retry de rede de EMITIR moeda duas vezes. Um aporte
   * duplicado não seria detectável depois: a reconciliação continuaria verde, porque ledger e
   * projeção estariam ambos errados na mesma direção.
   *
   * @param chaveIdempotencia já derivada por {@code ChaveIdempotencia.aportePatrocinador}, nunca a
   *     chave crua do cliente
   * @return saldo e id do lançamento, com {@code replay = true} se a chave já existia
   */
  ResultadoAporte aportar(
      UUID patrocinadorUsuarioId, long tokens, String chaveIdempotencia, Instant agora);
}
