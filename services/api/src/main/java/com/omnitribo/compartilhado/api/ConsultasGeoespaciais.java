package com.omnitribo.compartilhado.infra;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * ÚNICO ponto do sistema que chama funções PostGIS. Nenhum {@code ST_*} existe fora desta classe.
 *
 * <p>Trocar PostGIS por Oracle Spatial é reescrever este arquivo e mais nada: {@code ST_DWithin} →
 * {@code SDO_WITHIN_DISTANCE}, {@code ST_Distance} → {@code SDO_GEOM.SDO_DISTANCE}. Substitui a
 * regra "um repositório geo por módulo" do ADR 0002, que espalharia as chamadas por dois arquivos —
 * ver ADR 0007.
 *
 * <p>Mora em compartilhado/infra porque missoes e geolocalizacao precisam dos dois lados da mesma
 * álgebra. Atenção a uma sutileza que restringe o desenho: a regra do ArchUnit é DIRECIONAL.
 * compartilhado é isento como ALVO, mas esta classe continua {@code
 * resideOutsideOfPackage("com.omnitribo.missoes..")} como ORIGEM. Consequência concreta e
 * inegociável: ela NÃO pode importar {@code Missao}, {@code StatusMissao} nem {@code
 * CategoriaMissao}. Por isso status e categoria entram como String — sempre {@code .name()} de um
 * enum já validado pelo binder, nunca texto livre do cliente — e o retorno é {@link AlvoProximo},
 * um par neutro id+distância que o chamador reidrata.
 *
 * <p>Não é um repositório do Spring Data: {@code @Query(nativeQuery=true)} exige uma interface
 * ligada a uma {@code @Entity}, e a única entidade visível daqui seria {@code Outbox}. Amarrar a
 * busca geoespacial a Outbox para satisfazer a letra da convenção do CLAUDE.md seria pior que a
 * convenção. Usa {@link JdbcClient}, que participa da transação corrente via DataSourceUtils. A
 * parte da regra que de fato protege alguma coisa — parâmetros nomeados, zero concatenação — está
 * integralmente preservada. Ver ADR 0007.
 */
@Component
public class ConsultasGeoespaciais {

  /** Par neutro: quem, e a que distância em metros. Sem tipo de módulo algum. */
  public record AlvoProximo(UUID id, double distanciaM) {}

  /**
   * Coordenada nua, pelo mesmo motivo de {@link AlvoProximo}: nenhum tipo de módulo cruza daqui.
   */
  public record Centro(double lat, double lon) {}

  /**
   * Missões abertas dentro do raio, da mais próxima para a mais distante.
   *
   * <p>{@code ST_DWithin} sobre coluna {@code geography} recebe o raio em METROS e usa o índice
   * GiST {@code idx_missao_origem} (V8); {@code ST_Distance} devolve METROS. Nenhuma conversão de
   * unidade acontece em Java. A prova de que o índice é de fato escolhido pelo planner está em
   * docs/evidencias/f6-explain-analyze.md, gerada por IndiceGeoespacialTest.
   *
   * <p>Os {@code CAST(:param AS ...)} não são decoração: é a mesma defesa já documentada em
   * MissaoRepository.buscarComFiltros. Um parâmetro nulo sem tipo chega ao PostgreSQL como bytea e
   * a consulta estoura com "function ... does not exist". O cast tipa o parâmetro mesmo quando o
   * valor é nulo — o caso de {@code :categoria}, que é filtro opcional.
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
   * Pontos de custódia ATIVOS dentro do raio, do mais próximo para o mais distante.
   *
   * <p>Usa {@code idx_ponto_custodia_ponto}, o índice GiST criado na V8 — a tabela tinha índice
   * geoespacial desde a F3 e nenhuma consulta que o usasse.
   *
   * <p>{@code ativo} entra no WHERE e não é opcional: um ponto desativado no mapa levaria o
   * executor a uma loja que não recebe mais encomenda.
   */
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
   * Centro geográfico de uma tribo, DERIVADO em vez de armazenado.
   *
   * <p>A tabela {@code tribo} tem nome e bairro, nunca uma coordenada. Guardar uma exigiria
   * migration, alguém para preenchê-la e uma decisão sobre o que fazer quando a tribo crescer para
   * outro lado do bairro. O centroide das missões e dos pontos de custódia da tribo responde à
   * pergunta que a tela faz — "para onde aponto o mapa quando não sei onde o usuário está?" — e se
   * atualiza sozinho conforme a tribo se move.
   *
   * <p>{@code ST_Collect} sobre zero linhas devolve NULL, o {@code WHERE} externo filtra, e o
   * método devolve vazio: tribo recém-criada, sem missão nem ponto, não tem centro nenhum — e
   * inventar um seria pior do que o chamador cair no default configurado.
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

  public ConsultasGeoespaciais(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

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

  /**
   * Vazio quando a tribo ainda não tem missão nem ponto de custódia. Ver {@link
   * #SQL_CENTRO_DA_TRIBO}.
   */
  public Optional<Centro> centroDaTribo(UUID triboId) {
    return jdbc.sql(SQL_CENTRO_DA_TRIBO)
        .param("tribo", triboId)
        .query((rs, linha) -> new Centro(rs.getDouble("lat"), rs.getDouble("lon")))
        .optional();
  }

  /**
   * Distância esférica em metros entre dois pares lat/lon.
   *
   * <p>Recebe quatro escalares e NÃO tem cláusula FROM — de propósito, não por preguiça. O check-in
   * roda numa transação REQUIRES_NEW enquanto a transação chamadora segura {@code SELECT ... FOR
   * UPDATE} sobre a linha da missão. Se este método lesse a tabela {@code missao}, seriam duas
   * conexões disputando a mesma linha e o deadlock seria garantido. Quem chama já tem a origem
   * carregada e passa como valor.
   */
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
