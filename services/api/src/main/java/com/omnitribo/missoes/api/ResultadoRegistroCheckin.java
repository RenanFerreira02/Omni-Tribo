package com.omnitribo.missoes.api;

import com.omnitribo.compartilhado.api.RecursoAuditavel;
import com.omnitribo.geolocalizacao.api.MotivoRejeicaoCheckin;
import java.math.BigDecimal;
import java.util.UUID;

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
    BigDecimal acuraciaM)
    implements RecursoAuditavel {

  /**
   * Fecha a SEGUNDA metade do {@code @Auditavel}, que faltava e que o compilador não cobra.
   *
   * <p>{@code MissaoService.registrarCheckin} é anotado com {@code @Auditavel(acao =
   * "MISSAO_CHECKIN", entidade = "missao")}, mas o tipo devolvido não implementava {@code
   * RecursoAuditavel} — então {@code AuditoriaAspecto} gravava {@code entidade_id} <b>NULL</b> em
   * TODA linha de auditoria de check-in. Dos 14 métodos auditáveis do sistema, era o único
   * quebrado, e logo o do evento cuja trilha o projeto inteiro se dobra para preservar: a auditoria
   * sabia que alguém fez check-in e não dizia em qual missão.
   *
   * <p>Devolve o id da MISSÃO, não do check-in, porque é o recurso que a anotação declara auditar —
   * e é por ele que se reconstrói um incidente.
   */
  @Override
  public UUID idAuditoria() {
    return missao().id();
  }

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
