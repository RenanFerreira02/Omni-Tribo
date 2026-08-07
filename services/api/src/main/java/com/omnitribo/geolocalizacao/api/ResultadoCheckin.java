package com.omnitribo.geolocalizacao.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Veredito de um check-in já gravado.
 *
 * <p>A rejeição volta AQUI, como dado, em vez de subir como exceção. É o que permite ao chamador
 * responder 422 e fazer rollback da própria transação sem apagar a linha de auditoria, que foi
 * gravada numa transação separada e já commitada.
 */
public record ResultadoCheckin(
    UUID checkinId,
    Veredito veredito,
    BigDecimal distanciaM,
    BigDecimal velocidadeImplicitaKmh,
    String motivoRejeicao,
    /** true quando a chave de idempotência já existia: nenhuma linha nova foi gravada. */
    boolean replay) {

  public enum Veredito {
    ACEITO,
    /**
     * Aceito, porém com cinemática implausível. A missão transiciona normalmente e o cliente não é
     * avisado — contar ao fraudador que ele foi sinalizado ensina exatamente quanto desacelerar.
     */
    ACEITO_SUSPEITO,
    REJEITADO
  }

  public boolean aceito() {
    return veredito != Veredito.REJEITADO;
  }

  public boolean suspeito() {
    return veredito == Veredito.ACEITO_SUSPEITO;
  }
}
