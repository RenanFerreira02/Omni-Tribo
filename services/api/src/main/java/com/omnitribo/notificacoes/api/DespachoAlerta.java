package com.omnitribo.notificacoes.api;

import java.util.UUID;

/**
 * Porta pública do módulo notificacoes: transforma um evento já durável em notificação.
 *
 * <p>Existe por causa da DIREÇÃO da regra do ArchUnit. Quem drena a outbox é {@code
 * DrenadorOutboxService}, em {@code compartilhado} — que é isento como ALVO, mas continua sendo
 * ORIGEM. Injetar a implementação faria {@code compartilhado} nomear {@code notificacoes.dominio}
 * no bytecode e reprovar {@code RegrasArquiteturaTest}. Injetando esta interface, o drenador nunca
 * conhece a classe concreta.
 *
 * <p>A assinatura recebe PRIMITIVOS, e não a entidade {@code Outbox}, de propósito: o inverso
 * amarraria notificacoes ao modelo de persistência de compartilhado e faria qualquer mudança na
 * tabela da outbox atravessar a fronteira do módulo. O que notificacoes precisa saber de um evento
 * é o que ele é, sobre o que ele é, e o que ele carrega.
 */
public interface DespachoAlerta {

  /**
   * Entrega um evento como notificação.
   *
   * <p>Roda na transação do drenador. Tipo desconhecido LANÇA, e isso é contrato: o drenador
   * captura e devolve o evento à fila com backoff, registrando o motivo em {@code ultimo_erro}.
   * Engolir em silêncio perderia um fato que o resto do sistema já considera consumado.
   *
   * @param tipoEvento discriminador do evento, ex. {@code "MissaoConcluida"}
   * @param agregadoId id do agregado que originou o evento
   * @param payloadJson corpo do evento, como gravado na outbox
   */
  void despachar(String tipoEvento, UUID agregadoId, String payloadJson);
}
