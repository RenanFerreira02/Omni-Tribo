package com.omnitribo.missoes.api;

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
 */
public record ResultadoRegistroCheckin(
    MissaoResponse missao, boolean aceito, String motivoRejeicao) {

  public static ResultadoRegistroCheckin aceito(MissaoResponse missao) {
    return new ResultadoRegistroCheckin(missao, true, null);
  }

  public static ResultadoRegistroCheckin rejeitado(MissaoResponse missao, String motivo) {
    return new ResultadoRegistroCheckin(missao, false, motivo);
  }
}
