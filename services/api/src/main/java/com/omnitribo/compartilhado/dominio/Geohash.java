package com.omnitribo.compartilhado.dominio;

import java.math.BigDecimal;

/**
 * Geohash (base-32 de Gustavo Niemeyer) — usado EXCLUSIVAMENTE como chave de cache.
 *
 * <p>Precisão 7 = 35 bits (18 para longitude, 17 para latitude) = célula de ~153 m × 153 m no
 * equador e ~140 m × 153 m na latitude de São Paulo, onde o grau de longitude encolhe por
 * cos(23,5°). Precisão 6 daria célula de ~1,2 km — grossa demais para um raio de 2 km. Precisão 8
 * daria ~38 m × 19 m, e como dois fixes de GPS do mesmo aparelho parado diferem rotineiramente mais
 * que isso, a taxa de acerto do cache tenderia a zero.
 *
 * <p>Implementação própria em vez de biblioteca, deliberadamente. Os artefatos verificáveis no
 * Maven Central são {@code ch.hsr:geohash} (sem release desde 2016) e {@code
 * com.github.davidmoten:geo}. Ambos custariam dependência, entrada de licença e superfície de
 * SpotBugs para obter ~40 linhas de bisseção usadas para uma coisa só: compor chave de cache. Um
 * geohash errado degrada a taxa de acerto; não produz resposta errada, porque todo MISS recalcula
 * do PostGIS real.
 *
 * <p>LIMITAÇÃO CONHECIDA, documentada aqui e não escondida na camada de cache: célula de geohash é
 * um RETÂNGULO, não um círculo, e as bordas são fixas no globo, não centradas em quem consulta.
 * Duas consequências, ambas aceitáveis e nenhuma delas capaz de produzir resposta incorreta:
 *
 * <ul>
 *   <li>Dois pontos a 10 m um do outro, em lados opostos de uma borda, caem em células diferentes.
 *       Custa um cache MISS — nunca uma resposta errada.
 *   <li>Dois pontos a até ~216 m (a diagonal da célula) compartilham a mesma entrada. Então uma
 *       missão exatamente na borda do raio de BUSCA pode entrar ou sair do resultado por até essa
 *       margem, durante os 30 s de TTL. Nenhuma regra de negócio depende da borda do raio de busca;
 *       o raio de CHECK-IN é validado com ST_Distance exato, sem passar por cache algum.
 * </ul>
 */
public final class Geohash {

  /** Precisão usada na chave de cache de proximidade. Ver justificativa no javadoc da classe. */
  public static final int PRECISAO_CACHE = 7;

  // Alfabeto base-32 do geohash: dígitos e letras minúsculas menos 'a', 'i', 'l' e 'o', escolhidas
  // fora do conjunto por serem visualmente ambíguas.
  private static final char[] BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray();

  private static final int BITS_POR_CARACTERE = 5;

  private Geohash() {}

  /** Célula de cache do ponto, na precisão {@link #PRECISAO_CACHE}. */
  public static String celulaDeCache(BigDecimal latitude, BigDecimal longitude) {
    return codificar(latitude, longitude, PRECISAO_CACHE);
  }

  /**
   * Codifica lat/lon no geohash da precisão pedida.
   *
   * <p>Bisseção alternada: cada bit divide ao meio o intervalo da dimensão da vez e registra em
   * qual metade o ponto caiu. Começa pela LONGITUDE — é a convenção do formato, e inverter
   * produziria um hash silenciosamente errado, a mesma armadilha que {@link Coordenadas} existe
   * para evitar.
   */
  public static String codificar(BigDecimal latitude, BigDecimal longitude, int precisao) {
    if (latitude == null || longitude == null) {
      throw new IllegalArgumentException("Latitude e longitude são obrigatórias para o geohash.");
    }
    if (precisao < 1) {
      throw new IllegalArgumentException("Precisão do geohash deve ser positiva.");
    }

    double lat = latitude.doubleValue();
    double lon = longitude.doubleValue();

    double latMin = -90.0;
    double latMax = 90.0;
    double lonMin = -180.0;
    double lonMax = 180.0;

    StringBuilder hash = new StringBuilder(precisao);
    boolean vezDaLongitude = true;
    int bits = 0;
    int valorParcial = 0;

    while (hash.length() < precisao) {
      if (vezDaLongitude) {
        double meio = (lonMin + lonMax) / 2;
        if (lon >= meio) {
          valorParcial = (valorParcial << 1) | 1;
          lonMin = meio;
        } else {
          valorParcial = valorParcial << 1;
          lonMax = meio;
        }
      } else {
        double meio = (latMin + latMax) / 2;
        if (lat >= meio) {
          valorParcial = (valorParcial << 1) | 1;
          latMin = meio;
        } else {
          valorParcial = valorParcial << 1;
          latMax = meio;
        }
      }
      vezDaLongitude = !vezDaLongitude;

      if (++bits == BITS_POR_CARACTERE) {
        hash.append(BASE32[valorParcial]);
        bits = 0;
        valorParcial = 0;
      }
    }

    return hash.toString();
  }
}
