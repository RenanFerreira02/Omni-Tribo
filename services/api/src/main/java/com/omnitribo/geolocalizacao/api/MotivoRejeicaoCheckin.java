package com.omnitribo.geolocalizacao.api;

/**
 * Causa da rejeição de um check-in, em forma estável e legível por máquina.
 *
 * <p>Existe porque {@code motivoRejeicao} é texto em português para humano, e o app precisa
 * ramificar entre as causas: "desligue a localização simulada", "aproxime-se do local" e "tente a
 * céu aberto" são três instruções diferentes, e nenhuma delas se deduz do status 422. O caminho
 * proibido — e o motivo deste enum — é o app parsear o {@code detail}. Ver ADR 0010.
 *
 * <p>Vive em {@code api/} porque {@code missoes} o consome: é o módulo que expõe o endpoint de
 * check-in e traduz o veredito em resposta HTTP. A regra do ArchUnit permite atravessar módulo por
 * {@code api/}, nunca por {@code dominio/}.
 *
 * <p>Só as causas que REJEITAM entram aqui. Cinemática implausível não rejeita — marca o check-in
 * como suspeito e deixa passar, e o cliente não é avisado de propósito (contar ao fraudador que ele
 * foi sinalizado ensina exatamente quanto desacelerar).
 */
public enum MotivoRejeicaoCheckin {
  /** O dispositivo reportou que a posição é simulada. */
  LOCALIZACAO_SIMULADA,
  /** O raio de erro do fix é grande demais para sustentar afirmação de presença. */
  ACURACIA_INSUFICIENTE,
  /** A distância medida pelo PostGIS excede o raio de check-in da missão. */
  FORA_DO_RAIO
}
