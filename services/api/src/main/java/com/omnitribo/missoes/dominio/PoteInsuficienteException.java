package com.omnitribo.missoes.dominio;

import com.omnitribo.compartilhado.api.TipoProblema;
import com.omnitribo.compartilhado.dominio.RegraNegocioVioladaException;
import java.net.URI;
import java.util.Map;

/**
 * Operação recusada porque o pote não cobre a recompensa em token da missão.
 *
 * <p>Ganha {@code type} próprio pelo critério do ADR 0010 — uma URI por REAÇÃO DE UI —, e a razão é
 * a mesma de {@link NivelInsuficienteException}: a tela não faz o que faz nos outros 422. Ela não
 * pede para corrigir o pedido e tentar de novo, porque o pedido está certo. Ela oferece as duas
 * saídas reais: pedir financiamento a um vizinho da tribo, ou editar o rascunho para recompensar só
 * em XP (ADR 0035, ADR 0036). E a mesma requisição volta a funcionar sozinha, sem nada mudar no
 * corpo, assim que alguém financiar.
 *
 * <p>Dois caminhos chegam aqui, e é de propósito que sejam o mesmo tipo:
 *
 * <ul>
 *   <li>publicar uma missão comunitária cujo pote ainda não cobre a recompensa;
 *   <li>editar um rascunho de modo a REDUZIR a recompensa abaixo do que já foi financiado — o que
 *       deixaria o token do financiador preso numa missão que nunca o paga, perda que a
 *       reconciliação não enxerga porque ledger e projeção continuam batendo.
 * </ul>
 *
 * <p>Os dois números vão em {@code getPropriedades()}, como extensões do ProblemDetail, pelo mesmo
 * motivo que o check-in fora do raio devolve {@code distanciaM} e {@code raioM}: o app monta
 * "faltam N tokens" com aritmética própria, e ler número de dentro do {@code detail} acoplaria a UI
 * à revisão de copy do servidor.
 */
public class PoteInsuficienteException extends RegraNegocioVioladaException {

  private final long recompensaTokens;
  private final long poteTokens;

  public PoteInsuficienteException(String mensagem, long recompensaTokens, long poteTokens) {
    super(mensagem);
    this.recompensaTokens = recompensaTokens;
    this.poteTokens = poteTokens;
  }

  @Override
  public URI getTipo() {
    return TipoProblema.POTE_INSUFICIENTE;
  }

  @Override
  public Map<String, Object> getPropriedades() {
    return Map.of("recompensaTokens", recompensaTokens, "poteTokens", poteTokens);
  }
}
