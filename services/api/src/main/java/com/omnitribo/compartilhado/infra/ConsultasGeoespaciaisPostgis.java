package com.omnitribo.compartilhado.infra;

import com.omnitribo.compartilhado.api.ConsultasGeoespaciais;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Implementação PostGIS da porta {@link ConsultasGeoespaciais}. ÚNICO arquivo do sistema que chama
 * {@code ST_*}.
 *
 * <p>Não é um repositório do Spring Data: {@code @Query(nativeQuery=true)} exige uma interface
 * ligada a uma {@code @Entity}, e a única entidade visível daqui seria {@code Outbox}. Amarrar a
 * busca geoespacial a Outbox para satisfazer a letra da convenção do CLAUDE.md seria pior que a
 * convenção. Usa {@link JdbcClient}, que participa da transação corrente via DataSourceUtils. A
 * parte da regra que de fato protege alguma coisa — parâmetros nomeados, zero concatenação — está
 * integralmente preservada. Ver ADR 0007.
 *
 * <p>Os {@code CAST(:param AS ...)} não são decoração: um parâmetro nulo sem tipo chega ao
 * PostgreSQL como {@code bytea} e a consulta estoura com "function ... does not exist". O cast tipa
 * o parâmetro mesmo quando o valor é nulo — o caso de {@code :categoria}, que é filtro opcional.
 */
@Component
public class ConsultasGeoespaciaisPostgis implements ConsultasGeoespaciais {

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

  /**
   * Tribos com PRESENÇA dentro do raio — isto é, com pelo menos uma âncora (ponto de custódia ou
   * origem de missão) a até {@code raio} metros do alvo.
   *
   * <p>É o que permite responder "quem está perto deste ponto de custódia?" sem o usuário ter
   * coordenada: a tabela {@code usuario} não tem, e nenhuma coluna geográfica do schema descreve
   * onde uma PESSOA está agora. Ver ADR 0020.
   *
   * <p><b>Distância MÍNIMA, e não distância ao centroide.</b> A primeira versão usava o mesmo
   * centroide de {@link #SQL_CENTRO_DA_TRIBO}, e um teste a reprovou com um caso que o seed já
   * continha: a Tribo Pinheiros possui o locker da Consolação, ~3,8 km a leste, e o centroide
   * resultante fica a mais de 3 km da própria loja da tribo — de modo que uma encomenda no Leroy
   * Merlin Pinheiros NÃO notificava ninguém de Pinheiros, e notificava a Vila Madalena. O centroide
   * responde "onde é o meio da tribo", que é a pergunta certa para centralizar um mapa (e por isso
   * {@code centroDaTribo} continua existindo) e a errada para "esta tribo alcança este lugar".
   *
   * <p>Bairro real é espalhado e às vezes côncavo; o centro geométrico de uma região em U pode cair
   * fora dela. Distância mínima não tem essa patologia e ainda usa os índices GiST, porque o {@code
   * ST_DWithin} filtra ANTES do agrupamento.
   *
   * <p>{@code ST_DWithin} sobre {@code geography} recebe METROS, e {@code ST_Distance} devolve
   * METROS: nenhuma conversão de unidade acontece em Java.
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

  /**
   * Vazio quando a tribo ainda não tem missão nem ponto de custódia. Ver {@link
   * #SQL_CENTRO_DA_TRIBO}.
   */
  @Override
  public Optional<Centro> centroDaTribo(UUID triboId) {
    return jdbc.sql(SQL_CENTRO_DA_TRIBO)
        .param("tribo", triboId)
        .query((rs, linha) -> new Centro(rs.getDouble("lat"), rs.getDouble("lon")))
        .optional();
  }

  /**
   * Distância esférica em metros entre dois pares lat/lon.
   *
   * <p>Recebe quatro escalares e NÃO tem cláusula FROM — de propósito, não por preguiça. Quem chama
   * já segura {@code SELECT ... FOR UPDATE} sobre a linha da missão e já tem a origem carregada;
   * reler {@code missao} aqui seria uma segunda leitura da mesma linha travada, feita por uma
   * classe de {@code compartilhado} que não pode conhecer a tabela de nenhum módulo.
   *
   * <p>(Este método já foi documentado como rodando sob {@code REQUIRES_NEW}. Não roda, e nada no
   * caminho de valor roda — a propagação foi removida depois de o deadlock de pool derrubar até o
   * login. O desenho por valores continua certo, pelas duas razões acima.)
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
