package com.omnitribo.logistica.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Porta de leitura agregada sobre {@code entrega_falida}, para o painel de impacto.
 *
 * <p>Existe porque o painel mora em {@code compartilhado} e o ArchUnit proíbe alcançar {@code
 * logistica.dominio} ou {@code logistica.infra} de fora. Ver ADR 0029.
 *
 * <p><b>Só leitura, e agregada na origem.</b> Nenhum método aqui devolve entidade nem lista de
 * linhas cruas: o painel não tem o que fazer com uma {@code EntregaFalida}, e devolver a coleção
 * inteira transformaria uma consulta O(1) numa varredura carregada em memória.
 */
public interface EstatisticasEntregasFalidas {

  /** Contagem dos quatro desfechos, numa única statement. */
  ResumoEntregasFalidas resumo();

  /**
   * Instante do webhook, por missão, SÓ das entregas que viraram missão.
   *
   * <p>É a metade que {@code logistica} tem da mediana "webhook → check-in do executor"; a outra
   * metade mora em {@code geolocalizacao}, e quem as junta é o painel. O join em SQL seria mais
   * curto e cruzaria a fronteira dos módulos num lugar onde o ArchUnit não enxerga — ver ADR 0029.
   *
   * <p>{@code recebido_em} é o instante que a TRANSPORTADORA declarou no corpo do webhook, não o do
   * INSERT. É o que faz a métrica medir o tempo do bairro responder a uma falha, e não a latência
   * do nosso servidor; e é também o que a torna dependente de um relógio de terceiro.
   */
  Map<UUID, Instant> recebimentoPorMissao();
}
