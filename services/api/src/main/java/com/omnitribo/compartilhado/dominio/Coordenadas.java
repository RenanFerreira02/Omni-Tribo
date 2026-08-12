package com.omnitribo.compartilhado.dominio;

import java.math.BigDecimal;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

/**
 * Único ponto de construção e leitura de coordenadas geográficas do sistema.
 *
 * <p>Existe por causa de uma armadilha silenciosa: em JTS e em PostGIS, X é a LONGITUDE e Y é a
 * LATITUDE. Inverter a ordem não gera erro algum — gera uma missão em outro continente, e todo
 * check-in de F6 passaria a ser rejeitado por distância. Concentrar a conversão aqui deixa a
 * inversão impossível de espalhar e testável num único lugar.
 */
public final class Coordenadas {

  /** SRID 4326 — WGS 84, o mesmo das colunas geography(POINT,4326). */
  public static final int SRID_WGS84 = 4326;

  private static final GeometryFactory FABRICA =
      new GeometryFactory(new PrecisionModel(), SRID_WGS84);

  private Coordenadas() {}

  /** Constrói o Point na ordem correta (lon = X, lat = Y). */
  public static Point ponto(BigDecimal latitude, BigDecimal longitude) {
    if (latitude == null || longitude == null) {
      return null;
    }
    return FABRICA.createPoint(new Coordinate(longitude.doubleValue(), latitude.doubleValue()));
  }

  public static BigDecimal latitude(Point ponto) {
    return ponto == null ? null : BigDecimal.valueOf(ponto.getY());
  }

  public static BigDecimal longitude(Point ponto) {
    return ponto == null ? null : BigDecimal.valueOf(ponto.getX());
  }

  /**
   * Casas decimais para coordenada exibida a quem PARTICIPA — ~11 cm.
   *
   * <p>Mais que isso é ruído de ponto flutuante, não precisão: {@code double} carrega ~15 dígitos
   * significativos e devolvê-los sugere uma exatidão que o GPS de celular não tem.
   */
  public static final int CASAS_PRECISAS = 6;

  /**
   * Arredonda para exibição. Estava duplicado em {@code TriboService} e {@code
   * PontoCustodiaService}, cada um com a própria constante de casas e só um dos dois explicando o
   * motivo.
   *
   * <p><b>Arredondar não é anonimizar.</b> Seis casas ainda apontam a porta da casa; o que protege
   * quem publicou uma missão é reduzir as CASAS <b>e</b> omitir logradouro e CEP para quem não
   * participa — ver {@code MissaoResponse.de(Missao, UUID)}.
   */
  public static BigDecimal arredondar(BigDecimal valor, int casas) {
    return valor == null ? null : valor.setScale(casas, java.math.RoundingMode.HALF_UP);
  }

  /** Sobrecarga para quem tem o valor como {@code double} — o retorno do PostGIS. */
  public static BigDecimal arredondar(double valor, int casas) {
    return BigDecimal.valueOf(valor).setScale(casas, java.math.RoundingMode.HALF_UP);
  }
}
