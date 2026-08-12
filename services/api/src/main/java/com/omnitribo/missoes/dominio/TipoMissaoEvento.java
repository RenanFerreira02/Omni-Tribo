package com.omnitribo.missoes.dominio;

/**
 * Tipos gravados na trilha append-only {@code missao_evento}. Espelha o CHECK reescrito pela V20.
 *
 * <p>Saíram duas constantes que nenhum caminho produzia: {@code CHECK_IN_REJEITADO} — a rejeição de
 * check-in grava na tabela {@code checkin}, nunca aqui — e {@code CONCLUIDA}, reservada para uma F7
 * que já foi entregue gravando {@code CONFIRMADA}/{@code DISPUTA_RESOLVIDA}. Um tipo declarado e
 * inalcançável é falsa promessa de estado atingível para quem lê a trilha.
 */
public enum TipoMissaoEvento {
  PUBLICADA,
  ACEITA,
  DESISTIDA,
  INICIADA,
  CHECK_IN_REGISTRADO,
  CONFIRMADA,
  CONTESTADA,
  DISPUTA_RESOLVIDA,
  CANCELADA,

  /** Janela de OFERTA venceu: ninguém aceitou a missão a tempo. */
  EXPIRADA,

  /**
   * Executor aceitou, iniciou e ABANDONOU — passou do prazo sem check-in.
   *
   * <p>Tipo distinto de {@link #EXPIRADA} de propósito. Um único tipo genérico faria a trilha dizer
   * "expirou" para três causas com desfechos econômicos diferentes, e a reconciliação perderia a
   * capacidade de explicar POR QUE um pote voltou aos financiadores.
   */
  EXECUCAO_EXPIRADA,

  /** Executor cumpriu e fez check-in; o CRIADOR sumiu sem confirmar nem contestar. */
  CONFIRMACAO_EXPIRADA,

  /** ADMIN destravou manualmente uma missão parada, com justificativa. */
  DESTRAVADA_POR_ADMIN
}
