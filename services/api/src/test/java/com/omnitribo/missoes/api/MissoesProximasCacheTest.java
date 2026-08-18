package com.omnitribo.missoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import com.omnitribo.compartilhado.api.ConsultasGeoespaciais;
import com.omnitribo.compartilhado.dominio.Geohash;
import com.omnitribo.missoes.infra.CacheMissoesProximas;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Cache da busca por proximidade.
 *
 * <p>PRIMEIRO USO DE MOCKITO NA SUÍTE, e a exceção é justificada e estreita. "A segunda chamada não
 * tocou o banco" é uma afirmação sobre a contagem de invocações de um colaborador; não existe
 * estado observável que a expresse. Contar linhas não serve (nada é escrito), medir tempo é flaky,
 * e ler as estatísticas do próprio Caffeine provaria que o cache funciona — não que a consulta foi
 * evitada, que é o ponto. Todo o resto da F6 continua sem mock.
 *
 * <p>Classe separada de MissoesProximasTest porque o @MockitoSpyBean muda a chave do contexto do
 * Spring; misturar as duas forçaria um contexto novo para os testes que não precisam do spy.
 */
@Import(JwtTestConfig.class)
class MissoesProximasCacheTest extends TesteIntegracaoMvcBase {

  private static final UUID ALICE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
  private static final String BASE = "/api/v1/missoes";
  private static final String PROXIMAS = BASE + "/proximas";

  // Manaus, mesmo isolamento geográfico de MissoesProximasTest.
  //
  // As duas latitudes foram escolhidas DENTRO da mesma célula de geohash de precisão 7, não a
  // esmo: a célula mede 0,001373° (~153 m) e, nesta faixa, vai de -3,118744 a -3,117371. O par
  // óbvio (-3,1190 e -3,11873, a 30 m um do outro) cai em células ADJACENTES — é a limitação de
  // borda documentada em Geohash, que custa um MISS. Estas duas ficam com folga no meio da célula.
  private static final String LAT_CENTRO = "-3.1181";
  private static final String LON_CENTRO = "-60.0217";
  private static final String LAT_A_30M = "-3.11783";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired CacheMissoesProximas cacheMissoesProximas;

  @MockitoSpyBean ConsultasGeoespaciais consultasGeoespaciais;

  private final List<UUID> criadas = new ArrayList<>();

  @BeforeEach
  void limparCache() {
    // O cache é singleton do contexto, e o contexto é reaproveitado entre métodos de teste. Sem
    // isto, o segundo método herdaria a entrada quente do primeiro e passaria sem provar nada.
    cacheMissoesProximas.invalidarAgora();
  }

  @AfterEach
  void limparMissoes() {
    for (UUID id : criadas) {
      jdbcTemplate.update("DELETE FROM missao_evento WHERE missao_id = ?", id);
      jdbcTemplate.update("DELETE FROM missao WHERE id = ?", id);
    }
    criadas.clear();
  }

  @Test
  void segunda_busca_identica_nao_toca_o_banco() throws Exception {
    criarEPublicarEm(LAT_A_30M, LON_CENTRO);

    MvcResult primeira = buscar(LAT_CENTRO, LON_CENTRO, "2000");
    MvcResult segunda = buscar(LAT_CENTRO, LON_CENTRO, "2000");

    verify(consultasGeoespaciais, times(1))
        .missoesNoRaio(any(), any(), anyInt(), any(), any(), anyInt());

    // Não basta provar que a consulta não repetiu: é preciso provar que o que veio do cache é a
    // MESMA resposta. Sem isto, um obter() que devolvesse lista vazia no HIT passaria neste teste.
    assertThat(segunda.getResponse().getContentAsString())
        .isEqualTo(primeira.getResponse().getContentAsString());
    assertThat(idsRetornados(segunda)).isNotEmpty();
  }

  @Test
  void coordenada_na_mesma_celula_de_geohash_reaproveita_a_entrada() throws Exception {
    // Pré-condição explícita em vez de deslocamento chutado: se o par escolhido não caísse na
    // mesma célula, o teste falharia com "esperava 1 invocação, houve 2" e ninguém entenderia por
    // quê. Assim a causa aparece na própria mensagem.
    assertThat(Geohash.celulaDeCache(new BigDecimal(LAT_A_30M), new BigDecimal(LON_CENTRO)))
        .as("as duas coordenadas precisam estar na mesma célula para o teste fazer sentido")
        .isEqualTo(Geohash.celulaDeCache(new BigDecimal(LAT_CENTRO), new BigDecimal(LON_CENTRO)));

    buscar(LAT_CENTRO, LON_CENTRO, "2000");
    buscar(LAT_A_30M, LON_CENTRO, "2000");

    verify(consultasGeoespaciais, times(1))
        .missoesNoRaio(any(), any(), anyInt(), any(), any(), anyInt());
  }

  @Test
  void raio_diferente_nao_reaproveita_a_entrada() throws Exception {
    buscar(LAT_CENTRO, LON_CENTRO, "2000");
    buscar(LAT_CENTRO, LON_CENTRO, "3000");

    // Prova que o raio faz parte da chave. Sem isso, um raio de 3 km devolveria o resultado
    // calculado para 2 km e missões seriam omitidas silenciosamente.
    verify(consultasGeoespaciais, times(2))
        .missoesNoRaio(any(), any(), anyInt(), any(), any(), anyInt());
  }

  @Test
  void categoria_diferente_nao_reaproveita_a_entrada() throws Exception {
    buscarComCategoria(LAT_CENTRO, LON_CENTRO, "ENTREGA");
    buscarComCategoria(LAT_CENTRO, LON_CENTRO, "TRIBO");

    verify(consultasGeoespaciais, times(2))
        .missoesNoRaio(any(), any(), anyInt(), any(), any(), anyInt());
  }

  /**
   * O teste que protege o desenho da invalidação. Se alguém "simplificar" invalidarAposCommit numa
   * chamada direta a invalidateAll(), o TTL de 30 s passaria a servir resultado obsoleto e só este
   * teste perceberia.
   */
  @Test
  void publicar_missao_invalida_o_cache_e_a_nova_missao_aparece() throws Exception {
    MvcResult antes = buscar(LAT_CENTRO, LON_CENTRO, "2000");
    UUID nova = criarEPublicarEm(LAT_A_30M, LON_CENTRO);
    assertThat(idsRetornados(antes)).doesNotContain(nova);

    MvcResult depois = buscar(LAT_CENTRO, LON_CENTRO, "2000");

    assertThat(idsRetornados(depois)).contains(nova);
    verify(consultasGeoespaciais, times(2))
        .missoesNoRaio(any(), any(), anyInt(), any(), any(), anyInt());
  }

  // ─── Apoio ─────────────────────────────────────────────────────────────────────────────────

  private MvcResult buscar(String lat, String lon, String raio) throws Exception {
    return mockMvc
        .perform(
            get(PROXIMAS)
                .param("lat", lat)
                .param("lon", lon)
                .param("raioMetros", raio)
                .header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isOk())
        .andReturn();
  }

  private void buscarComCategoria(String lat, String lon, String categoria) throws Exception {
    mockMvc
        .perform(
            get(PROXIMAS)
                .param("lat", lat)
                .param("lon", lon)
                .param("categoria", categoria)
                .header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isOk());
  }

  private List<UUID> idsRetornados(MvcResult resultado) throws Exception {
    JsonNode raiz = JSON.readTree(resultado.getResponse().getContentAsString());
    List<UUID> ids = new ArrayList<>();
    for (JsonNode item : raiz) {
      ids.add(UUID.fromString(item.get("missao").get("id").asText()));
    }
    return ids;
  }

  private UUID criarEPublicarEm(String lat, String lon) throws Exception {
    Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant fim = inicio.plus(2, ChronoUnit.DAYS);
    String corpo =
        """
        {
          "categoria": "ENTREGA",
          "titulo": "Missao para invalidar o cache",
          "descricao": "Publicada no meio do teste de cache.",
          "valorBrl": 0.00,
          "pesoKg": 10.00,
          "volumeL": 40.00,
          "origemLat": %s,
          "origemLon": %s,
          "cep": "69005040",
          "logradouro": "Avenida Eduardo Ribeiro",
          "bairro": "Centro",
          "cidade": "Manaus",
          "uf": "AM",
          "raioCheckinM": 50,
          "janelaInicio": "%s",
          "janelaFim": "%s"
        }
        """
            .formatted(lat, lon, inicio, fim);

    MvcResult resultado =
        mockMvc
            .perform(
                post(BASE)
                    .header("Authorization", bearer(ALICE_ID))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo))
            .andExpect(status().isCreated())
            .andReturn();

    UUID id =
        UUID.fromString(
            JSON.readTree(resultado.getResponse().getContentAsString()).get("id").asText());
    criadas.add(id);

    mockMvc
        .perform(post(BASE + "/{id}/publicar", id).header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isOk());
    return id;
  }

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
