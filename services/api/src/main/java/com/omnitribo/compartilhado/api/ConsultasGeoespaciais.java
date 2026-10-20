package com.omnitribo.compartilhado.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PORTA de consulta geoespacial — o único caminho até álgebra de coordenadas (ADR 0007 e 0018).
 * Todo {@code ST_*} vive na implementação, num arquivo só.
 *
 * <p>Interface em {@code api/} porque domínios de quatro módulos a usam, e depender de adaptador de
 * outro módulo é a direção proibida. A regra do ArchUnit é DIRECIONAL: esta interface não importa
 * tipo de módulo — status e categoria entram como String ({@code .name()} de enum validado) e o
 * retorno é {@link AlvoProximo}, que o chamador reidrata.
 */
public interface ConsultasGeoespaciais {

  /** Par neutro: quem, e a que distância em metros. Sem tipo de módulo algum. */
  record AlvoProximo(UUID id, double distanciaM) {}

  /** Coordenada nua: nenhum tipo de módulo cruza daqui. */
  record Centro(double lat, double lon) {}

  /** Missões no raio, da mais próxima para a mais distante. Categoria nula = todas. Metros. */
  List<AlvoProximo> missoesNoRaio(
      BigDecimal lat, BigDecimal lon, int raioMetros, String status, String categoria, int limite);

  /** Pontos de custódia ATIVOS no raio, do mais próximo para o mais distante. */
  List<AlvoProximo> pontosCustodiaNoRaio(
      BigDecimal lat, BigDecimal lon, int raioMetros, int limite);

  /**
   * Tribos com PRESENÇA no raio (ADR 0020): menor distância entre o alvo e as âncoras da tribo, não
   * o centroide. Notificar por tribo não exige guardar onde ninguém está.
   */
  /**
   * Parceiros ATIVOS no raio, para o catálogo de benefícios. Distância calculada a cada consulta,
   * nunca armazenada — depende de quem pergunta.
   */
  List<AlvoProximo> parceirosNoRaio(BigDecimal lat, BigDecimal lon, int raioMetros, int limite);

  List<AlvoProximo> tribosNoRaio(BigDecimal lat, BigDecimal lon, int raioMetros, int limite);

  /** Centro DERIVADO de uma tribo, para CENTRALIZAR o mapa (não para decidir proximidade). */
  Optional<Centro> centroDaTribo(UUID triboId);

  /** Distância em metros entre dois pares lat/lon, sem ler tabela: quem chama já tem a origem. */
  double distanciaMetros(BigDecimal latA, BigDecimal lonA, BigDecimal latB, BigDecimal lonB);
}
