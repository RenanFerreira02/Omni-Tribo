package com.omnitribo.compartilhado.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unitário puro: sem Spring, sem container.
 *
 * <p>As asserções são de dois tipos, e nenhuma delas é um valor que eu tenha "observado o código
 * produzir" e congelado — isso apenas registraria o bug, se houvesse um. São: (a) o vetor de
 * referência canônico do formato, publicado e independente desta implementação; e (b) propriedades
 * que decorrem da geometria do geohash e valeriam para qualquer implementação correta.
 */
class GeohashTest {

  // Vetor de referência canônico do geohash (Jutland, Dinamarca): o exemplo publicado do formato.
  private static final BigDecimal LAT_REFERENCIA = new BigDecimal("57.64911");
  private static final BigDecimal LON_REFERENCIA = new BigDecimal("10.40744");
  private static final String HASH_REFERENCIA = "u4pruydqqvj";

  @Test
  void codifica_o_vetor_de_referencia_do_formato() {
    assertThat(Geohash.codificar(LAT_REFERENCIA, LON_REFERENCIA, HASH_REFERENCIA.length()))
        .isEqualTo(HASH_REFERENCIA);
  }

  @Test
  void celula_de_cache_e_o_vetor_de_referencia_truncado_em_7() {
    assertThat(Geohash.celulaDeCache(LAT_REFERENCIA, LON_REFERENCIA))
        .isEqualTo(HASH_REFERENCIA.substring(0, Geohash.PRECISAO_CACHE))
        .hasSize(Geohash.PRECISAO_CACHE);
  }

  /**
   * Propriedade estrutural: o geohash é hierárquico por construção (cada caractere refina a célula
   * anterior), então reduzir a precisão só pode truncar o prefixo. É o que autoriza pensar na
   * precisão como "nível de zoom".
   */
  @Test
  void precisao_menor_e_sempre_prefixo_da_maior() {
    for (int precisao = 1; precisao < HASH_REFERENCIA.length(); precisao++) {
      assertThat(Geohash.codificar(LAT_REFERENCIA, LON_REFERENCIA, precisao))
          .isEqualTo(HASH_REFERENCIA.substring(0, precisao));
    }
  }

  /**
   * A armadilha que {@link Coordenadas} existe para evitar, aplicada aqui: em geohash a bisseção
   * começa pela LONGITUDE. Trocar a ordem dos argumentos não gera erro nenhum — gera uma célula em
   * outro lugar do planeta, e o cache passaria a agrupar pontos sem relação alguma.
   */
  @Test
  void latitude_e_longitude_trocadas_produzem_celula_diferente() {
    String correto = Geohash.celulaDeCache(LAT_REFERENCIA, LON_REFERENCIA);
    String trocado = Geohash.celulaDeCache(LON_REFERENCIA, LAT_REFERENCIA);

    assertThat(trocado).isNotEqualTo(correto);
  }

  /**
   * Propriedade geométrica: na precisão 7 a célula mede ~153 m de lado, logo sua diagonal é ~216 m.
   * Dois pontos separados por mais que a diagonal NÃO PODEM compartilhar célula, qualquer que seja
   * a posição das bordas. 0,01° de latitude ≈ 1,11 km, folgadamente acima do limite — por isso esta
   * asserção não depende de onde as bordas caem, e não é flaky.
   */
  @Test
  void pontos_muito_mais_distantes_que_a_celula_caem_em_celulas_diferentes() {
    BigDecimal lat = new BigDecimal("-23.5629");
    BigDecimal lon = new BigDecimal("-46.6996");
    BigDecimal umQuiloDeLatitude = lat.add(new BigDecimal("0.01"));

    assertThat(Geohash.celulaDeCache(umQuiloDeLatitude, lon))
        .isNotEqualTo(Geohash.celulaDeCache(lat, lon));
  }

  @Test
  void mesma_coordenada_produz_sempre_a_mesma_celula() {
    BigDecimal lat = new BigDecimal("-3.1190");
    BigDecimal lon = new BigDecimal("-60.0217");

    assertThat(Geohash.celulaDeCache(lat, lon)).isEqualTo(Geohash.celulaDeCache(lat, lon));
  }

  /**
   * Escala diferente, mesmo valor numérico: {@code new BigDecimal("-23.56")} e {@code new
   * BigDecimal("-23.5600")} não são iguais por equals, mas descrevem o mesmo ponto. A célula tem de
   * ser a mesma, senão a chave de cache dependeria de como o cliente formatou o JSON.
   */
  @Test
  void escala_do_bigdecimal_nao_altera_a_celula() {
    assertThat(Geohash.celulaDeCache(new BigDecimal("-23.5600"), new BigDecimal("-46.6900")))
        .isEqualTo(Geohash.celulaDeCache(new BigDecimal("-23.56"), new BigDecimal("-46.69")));
  }

  @Test
  void coordenada_nula_e_precisao_invalida_sao_recusadas() {
    BigDecimal zero = BigDecimal.ZERO;

    assertThatThrownBy(() -> Geohash.celulaDeCache(null, zero))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Geohash.celulaDeCache(zero, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Geohash.codificar(zero, zero, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
