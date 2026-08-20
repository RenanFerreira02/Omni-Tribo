package com.omnitribo.carteira.api;

import java.time.Instant;
import java.util.Optional;
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
   * Trava a carteira do financiador e sonda a chave de idempotência, SEM escrever nada.
   *
   * <p><b>Existe para consertar a ordem.</b> A sondagem morava dentro de {@link #debitar}, ou seja,
   * DEPOIS das validações de estado da missão. Consequência concreta: o retry de rede de um
   * financiamento que completou o pote encontrava o pote já cheio, batia no teto e recebia
   * <b>422</b> em vez do replay que o contrato promete. Mesma coisa se a missão tivesse sido
   * cancelada nesse meio-tempo. O valor nunca era duplicado — o débito continuava barrado pela
   * sondagem sob lock —, mas o cliente recebia um erro para uma operação que já tinha dado certo, e
   * é impossível distinguir isso de uma falha real.
   *
   * <p>A ordem canônica do projeto é <b>adquira os locks → sonde → valide → escreva</b>, e é o que
   * {@code SaqueService}, {@code TransferenciaService} e {@code CreditoRecompensaService} já
   * faziam. Só o financiamento destoava.
   *
   * @return o resultado do lançamento anterior quando a chave já existe; vazio quando é a primeira
   *     vez e o chamador deve seguir para {@link #debitar}
   */
  Optional<ResultadoFinanciamento> sondar(UUID financiadorId, String chaveIdempotencia);

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

  /**
   * Debita o PATROCINADOR para financiar o pote de uma missão de retirada.
   *
   * <p>Método separado de {@link #debitar}, e não um parâmetro de motivo, porque o motivo é um tipo
   * de {@code carteira.dominio} e {@code missoes} não pode importá-lo — a regra do ArchUnit é
   * direcional. O lançamento sai com {@code FINANCIAMENTO_PATROCINADOR}, que o estorno enxerga por
   * {@code LancamentoRepository.buscarFinanciamentosDaMissao}.
   *
   * <p><b>Saldo insuficiente devolve VAZIO, não lança.</b> É a diferença central em relação a
   * {@link #debitar}, e segue a doutrina de "recusa que precisa ser gravada volta como valor": a
   * encomenda já está fisicamente na loja quando o webhook chega, e a falta de saldo do
   * patrocinador é um desfecho de negócio (SEM_PATROCINIO, HTTP 200) que precisa ser REGISTRADO na
   * entrega falida. Lançar aqui abortaria a transação e apagaria justamente o registro que a
   * transportadora precisa ler para saber que reenviar não adianta.
   *
   * <p>Trava a carteira do patrocinador com {@code PESSIMISTIC_WRITE} e essa é a PRIMEIRA leitura
   * dela na transação. A ordem de lock desta operação é {@code ponto_custodia → carteira}: a linha
   * da missão ainda não existe no banco quando este método roda — o UUID dela é gerado pelo
   * chamador — então a ordem global {@code missao → carteira} não é violada, porque não há missão
   * para disputar. Ver ADR 0024 §7.
   *
   * <p>Consequência de throughput aceita conscientemente: todo webhook da mesma transportadora
   * serializa nesta única linha de carteira.
   *
   * @param tokens deve ser positivo. Recompensa zero não chama este método — um lançamento de valor
   *     zero consumiria uma chave de idempotência sem mover nada, e {@code
   *     ck_lancamento_valor_nao_nulo} o recusaria.
   * @return o resultado do débito, ou vazio quando o saldo não cobre {@code tokens}
   */
  Optional<ResultadoFinanciamento> debitarPatrocinador(
      UUID patrocinadorUsuarioId,
      UUID missaoId,
      long tokens,
      String chaveIdempotencia,
      Instant agora);
}
