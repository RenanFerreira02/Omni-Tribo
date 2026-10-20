package com.omnitribo.compartilhado.infra;

import com.omnitribo.compartilhado.api.ConsultasGeoespaciais;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Implementação PostGIS de {@link ConsultasGeoespaciais} — ÚNICO arquivo que chama {@code ST_*}
 * (ADR 0007). {@link JdbcClient} com parâmetros nomeados e zero concatenação. {@code CAST(:x AS
 * ...)} porque parâmetro nulo sem tipo chega como bytea e a consulta estoura.
 */
@Component
public class ConsultasGeoespaciaisPostgis implements ConsultasGeoespaciais {

  /**
   * Missões abertas no raio, da mais próxima para a mais distante. {@code ST_DWithin}/{@code
   * ST_Distance} sobre geography trabalham em METROS e usam o GiST {@code idx_missao_origem}
   * (provado por {@code IndiceGeoespacialTest}).
   */
  static final String SQL_MISSOES_NO_RAIO =
      """
      SELECT m.id AS id,
             ST_Distance(
                 m.origem,
                 ST_SetSRID(ST_MakePoint(CAST(:lon AS double precision),
                                         CAST(:lat AS double precision)), 4326)::geography
             ) AS distancia_m
        FROM missao m
       WHERE ST_DWithin(
                 m.origem,
                 ST_SetSRID(ST_MakePoint(CAST(:lon AS double precision),
                                         CAST(:lat AS double precision)), 4326)::geography,
                 CAST(:raio AS double precision))
         AND m.status = CAST(:status AS varchar)
         AND (CAST(:categoria AS varchar) IS NULL OR m.categoria = CAST(:categoria AS varchar))
       ORDER BY distancia_m ASC
       LIMIT CAST(:limite AS integer)
      """;

  /**
   * Pontos de custódia ATIVOS no raio, pelo GiST da V8 — ponto inativo levaria a uma loja fechada.
   */
  static final String SQL_PARCEIROS_NO_RAIO =
      """
      SELECT p.id AS id,
             ST_Distance(
                 p.ponto,
                 ST_SetSRID(ST_MakePoint(CAST(:lon AS double precision),
                                         CAST(:lat AS double precision)), 4326)::geography
             ) AS distancia_m
        FROM parceiro p
       WHERE ST_DWithin(
                 p.ponto,
                 ST_SetSRID(ST_MakePoint(CAST(:lon AS double precision),
                                         CAST(:lat AS double precision)), 4326)::geography,
                 CAST(:raio AS double precision))
         AND p.ativo = true
       ORDER BY distancia_m ASC
       LIMIT CAST(:limite AS integer)
      """;

  static final String SQL_PONTOS_CUSTODIA_NO_RAIO =
      """
      SELECT p.id AS id,
             ST_Distance(
                 p.ponto,
                 ST_SetSRID(ST_MakePoint(CAST(:lon AS double precision),
                                         CAST(:lat AS double precision)), 4326)::geography
             ) AS distancia_m
        FROM ponto_custodia p
       WHERE ST_DWithin(
                 p.ponto,
                 ST_SetSRID(ST_MakePoint(CAST(:lon AS double precision),
                                         CAST(:lat AS double precision)), 4326)::geography,
                 CAST(:raio AS double precision))
         AND p.ativo = true
       ORDER BY distancia_m ASC
       LIMIT CAST(:limite AS integer)
      """;

  /**
   * Centro DERIVADO de uma tribo: centroide das missões e pontos dela, sem coluna a manter. Sem
   * missão nem ponto não há centro, e o chamador usa o default.
   */
  static final String SQL_CENTRO_DA_TRIBO =
      """
      SELECT ST_Y(c.centro) AS lat, ST_X(c.centro) AS lon
        FROM (SELECT ST_Centroid(ST_Collect(pontos.geo::geometry)) AS centro
                FROM (SELECT pc.ponto AS geo
                        FROM ponto_custodia pc
                       WHERE pc.tribo_id = CAST(:tribo AS uuid)
                         AND pc.ativo = true
                       UNION ALL
                      SELECT m.origem AS geo
                        FROM missao m
                        JOIN usuario u ON u.id = m.criador_id
                       WHERE u.tribo_id = CAST(:tribo AS uuid)) pontos) c
       WHERE c.centro IS NOT NULL
      """;

  /**
   * Tribos com pelo menos uma âncora (ponto ou origem de missão) no raio (ADR 0020). Distância
   * MÍNIMA, não ao centroide: o centroide de um bairro espalhado pode cair longe da própria loja. O
   * {@code ST_DWithin} filtra antes do agrupamento, então o índice é usado.
   */
  static final String SQL_TRIBOS_NO_RAIO =
      """
      SELECT pontos.tribo_id AS id,
             MIN(ST_Distance(
                 pontos.geo,
                 ST_SetSRID(ST_MakePoint(CAST(:lon AS double precision),
                                         CAST(:lat AS double precision)), 4326)::geography
             )) AS distancia_m
        FROM (SELECT pc.tribo_id, pc.ponto AS geo
                FROM ponto_custodia pc
               WHERE pc.tribo_id IS NOT NULL
                 AND pc.ativo = true
               UNION ALL
              SELECT u.tribo_id, m.origem AS geo
                FROM missao m
                JOIN usuario u ON u.id = m.criador_id
               WHERE u.tribo_id IS NOT NULL) pontos
       WHERE ST_DWithin(
                 pontos.geo,
                 ST_SetSRID(ST_MakePoint(CAST(:lon AS double precision),
                                         CAST(:lat AS double precision)), 4326)::geography,
                 CAST(:raio AS double precision))
       GROUP BY pontos.tribo_id
       ORDER BY distancia_m ASC
       LIMIT CAST(:limite AS integer)
      """;

  private static final String SQL_DISTANCIA =
      """
      SELECT ST_Distance(
                 ST_SetSRID(ST_MakePoint(CAST(:lonA AS double precision),
                                         CAST(:latA AS double precision)), 4326)::geography,
                 ST_SetSRID(ST_MakePoint(CAST(:lonB AS double precision),
                                         CAST(:latB AS double precision)), 4326)::geography
             ) AS distancia_m
      """;

  private final JdbcClient jdbc;

  public ConsultasGeoespaciaisPostgis(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<AlvoProximo> missoesNoRaio(
      BigDecimal lat, BigDecimal lon, int raioMetros, String status, String categoria, int limite) {
    return jdbc.sql(SQL_MISSOES_NO_RAIO)
        .param("lat", lat)
        .param("lon", lon)
        .param("raio", raioMetros)
        .param("status", status)
        .param("categoria", categoria)
        .param("limite", limite)
        .query(
            (rs, linha) ->
                new AlvoProximo(rs.getObject("id", UUID.class), rs.getDouble("distancia_m")))
        .list();
  }

  @Override
  public List<AlvoProximo> pontosCustodiaNoRaio(
      BigDecimal lat, BigDecimal lon, int raioMetros, int limite) {
    return jdbc.sql(SQL_PONTOS_CUSTODIA_NO_RAIO)
        .param("lat", lat)
        .param("lon", lon)
        .param("raio", raioMetros)
        .param("limite", limite)
        .query(
            (rs, linha) ->
                new AlvoProximo(rs.getObject("id", UUID.class), rs.getDouble("distancia_m")))
        .list();
  }

  @Override
  public List<AlvoProximo> parceirosNoRaio(
      BigDecimal lat, BigDecimal lon, int raioMetros, int limite) {
    return jdbc.sql(SQL_PARCEIROS_NO_RAIO)
        .param("lat", lat)
        .param("lon", lon)
        .param("raio", raioMetros)
        .param("limite", limite)
        .query(
            (rs, linha) ->
                new AlvoProximo(rs.getObject("id", UUID.class), rs.getDouble("distancia_m")))
        .list();
  }

  @Override
  public List<AlvoProximo> tribosNoRaio(
      BigDecimal lat, BigDecimal lon, int raioMetros, int limite) {
    return jdbc.sql(SQL_TRIBOS_NO_RAIO)
        .param("lat", lat)
        .param("lon", lon)
        .param("raio", raioMetros)
        .param("limite", limite)
        .query(
            (rs, linha) ->
                new AlvoProximo(rs.getObject("id", UUID.class), rs.getDouble("distancia_m")))
        .list();
  }

  /** Vazio quando a tribo não tem missão nem ponto. */
  @Override
  public Optional<Centro> centroDaTribo(UUID triboId) {
    return jdbc.sql(SQL_CENTRO_DA_TRIBO)
        .param("tribo", triboId)
        .query((rs, linha) -> new Centro(rs.getDouble("lat"), rs.getDouble("lon")))
        .optional();
  }

  /**
   * Distância em metros entre dois pares lat/lon, sem FROM: quem chama já tem a origem e segura o
   * lock da missão — reler a linha seria uma segunda leitura de tabela que esta classe nem conhece.
   */
  @Override
  public double distanciaMetros(
      BigDecimal latA, BigDecimal lonA, BigDecimal latB, BigDecimal lonB) {
    return jdbc.sql(SQL_DISTANCIA)
        .param("latA", latA)
        .param("lonA", lonA)
        .param("latB", latB)
        .param("lonB", lonB)
        .query(Double.class)
        .single();
  }
}
