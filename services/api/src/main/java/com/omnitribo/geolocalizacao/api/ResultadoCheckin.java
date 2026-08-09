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
    /**
     * Acurácia REPORTADA pelo dispositivo, ecoada de volta. Existe para o chamador poder explicar a
     * recusa por acurácia com o número que o próprio aparelho informou — inclusive num replay, em
     * que ela vem da linha persistida e não de uma leitura nova.
     */
    BigDecimal acuraciaM,
    BigDecimal velocidadeImplicitaKmh,
    /** Causa estável da rejeição; null quando aceito. É ela que escolhe o `type`. Ver ADR 0010. */
    MotivoRejeicaoCheckin codigoRejeicao,
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
