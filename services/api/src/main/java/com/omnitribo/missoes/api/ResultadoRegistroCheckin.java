package com.omnitribo.missoes.api;

import com.omnitribo.geolocalizacao.api.MotivoRejeicaoCheckin;
import java.math.BigDecimal;

/**
 * Desfecho de um check-in, devolvido pelo serviço em vez de lançado como exceção.
 *
 * <p>Existe por uma razão transacional, não estética. A rejeição precisa de duas coisas que uma
 * transação não faz junto: COMMITAR a linha de auditoria em {@code checkin} e FALHAR a requisição
 * com 422. Lançar de dentro da transação apagaria a linha; e a solução anterior — gravar numa
 * transação {@code REQUIRES_NEW} aninhada — consumia duas conexões por check-in e travava o pool
 * sob concorrência (ver {@code CheckinConcorrenteTest} e o javadoc de {@code
 * RegistroCheckinService}).
 *
 * <p>Então o serviço devolve o veredito, a transação commita nos dois casos, e o controller lança o
 * 422 DEPOIS do commit. Uma transação, uma conexão, e a trilha antifraude preservada.
 *
 * <p>{@code codigoRejeicao} acompanha o texto porque é ele que escolhe o {@code type} da resposta —
 * o app ramifica por ele, nunca pelo {@code motivoRejeicao}, que é copy. Ver ADR 0010.
 */
public record ResultadoRegistroCheckin(
    MissaoResponse missao,
    boolean aceito,
    MotivoRejeicaoCheckin codigoRejeicao,
    String motivoRejeicao,
    /**
     * Medidas que acompanham a recusa, para o controller as expor como campos do ProblemDetail. São
     * nulas quando aceito — e podem ser nulas mesmo na recusa, se a linha antiga de um replay não
     * as tiver. Ver {@code CheckinRejeitadoException.de}.
     */
    BigDecimal distanciaM,
    BigDecimal acuraciaM) {

  public static ResultadoRegistroCheckin aceito(MissaoResponse missao) {
    return new ResultadoRegistroCheckin(missao, true, null, null, null, null);
  }

  public static ResultadoRegistroCheckin rejeitado(
      MissaoResponse missao,
      MotivoRejeicaoCheckin codigo,
      String motivo,
      BigDecimal distanciaM,
      BigDecimal acuraciaM) {
    return new ResultadoRegistroCheckin(missao, false, codigo, motivo, distanciaM, acuraciaM);
  }
}
