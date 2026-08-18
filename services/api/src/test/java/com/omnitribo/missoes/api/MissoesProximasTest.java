package com.omnitribo.missoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Busca por proximidade (radar).
 *
 * <p>DEFESA DE POLUIÇÃO EM DUAS CAMADAS, e as duas são necessárias. O container do Postgres é
 * singleton para a JVM inteira e nunca é truncado; o seed V900 insere 12 missões em Pinheiros; e
 * MissaoControllerTest cria missões na mesma coordenada sem apagá-las.
 *
 * <ol>
 *   <li><b>Isolamento geográfico.</b> Todos os fixtures ficam em Manaus, ~2 700 km de Pinheiros. O
 *       raio máximo que o endpoint aceita (20 km) não alcança nem o seed nem o resíduo de outras
 *       classes. Esta é a camada que de fato funciona, porque não depende da disciplina de limpeza
 *       de ninguém.
 *   <li><b>Asserção por id, nunca por contagem.</b> {@code containsExactly(perto, medio)} em vez de
 *       {@code hasSize(2)}. Uma asserção de tamanho seria bomba-relógio numa tabela compartilhada.
 * </ol>
 */
@Import(JwtTestConfig.class)
class MissoesProximasTest extends TesteIntegracaoMvcBase {

  private static final UUID ALICE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
  private static final String BASE = "/api/v1/missoes";
  private static final String PROXIMAS = BASE + "/proximas";

  // Centro em Manaus. Longe o bastante de Pinheiros (-23.56/-46.69) para que nenhum raio aceito
  // pelo endpoint alcance o seed ou o resíduo das outras classes de teste.
  private static final String LAT_CENTRO = "-3.1190";
  private static final String LON_CENTRO = "-60.0217";

  // 1° de latitude ≈ 111 320 m em qualquer longitude — por isso os deslocamentos são todos em
  // latitude, e a distância resultante não depende do meridiano escolhido.
  private static final String LAT_A_30M = "-3.11873"; // 0,00027° ≈ 30 m
  private static final String LAT_A_300M = "-3.11630"; // 0,00270° ≈ 300 m
  private static final String LAT_A_3KM = "-3.09190"; // 0,02700° ≈ 3 006 m

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  private final List<UUID> criadas = new ArrayList<>();

  @AfterEach
  void limpar() {
    for (UUID id : criadas) {
      jdbcTemplate.update("DELETE FROM missao_evento WHERE missao_id = ?", id);
      jdbcTemplate.update("DELETE FROM missao WHERE id = ?", id);
    }
    criadas.clear();
  }

  @Test
  void retorna_apenas_missoes_no_raio_ordenadas_por_distancia_crescente() throws Exception {
    UUID perto = criarEPublicarEm(LAT_A_30M, LON_CENTRO);
    UUID medio = criarEPublicarEm(LAT_A_300M, LON_CENTRO);
    UUID longe = criarEPublicarEm(LAT_A_3KM, LON_CENTRO);

    MvcResult resultado =
        mockMvc
            .perform(
                get(PROXIMAS)
                    .param("lat", LAT_CENTRO)
                    .param("lon", LON_CENTRO)
                    .param("raioMetros", "500")
                    .header("Authorization", bearer(ALICE_ID)))
            .andExpect(status().isOk())
            .andReturn();

    // Exatamente quais voltam, e em que ordem: a de 30 m antes da de 300 m.
    assertThat(idsRetornados(resultado)).containsExactly(perto, medio).doesNotContain(longe);

    JsonNode corpo = JSON.readTree(resultado.getResponse().getContentAsString());
    assertThat(corpo.get(0).get("distanciaM").asDouble()).isBetween(25.0, 35.0);
    assertThat(corpo.get(1).get("distanciaM").asDouble()).isBetween(295.0, 305.0);

    // A missão vem completa, não só o id: o app monta o card do radar com uma chamada só.
    assertThat(corpo.get(0).get("missao").get("status").asText()).isEqualTo("ABERTA");
    assertThat(corpo.get(0).get("missao").get("titulo").asText()).isNotBlank();
  }

  @Test
  void sem_raio_informado_usa_o_default_de_2000_metros() throws Exception {
    UUID medio = criarEPublicarEm(LAT_A_300M, LON_CENTRO);
    UUID longe = criarEPublicarEm(LAT_A_3KM, LON_CENTRO);

    MvcResult resultado =
        mockMvc
            .perform(
                get(PROXIMAS)
                    .param("lat", LAT_CENTRO)
                    .param("lon", LON_CENTRO)
                    .header("Authorization", bearer(ALICE_ID)))
            .andExpect(status().isOk())
            .andReturn();

    // 300 m entra no default de 2 km; 3 km não. Prova o valor do default, não só que existe um.
    assertThat(idsRetornados(resultado)).contains(medio).doesNotContain(longe);
  }

  @Test
  void rascunho_nunca_aparece_no_radar_nem_para_o_proprio_criador() throws Exception {
    UUID rascunho = criarMissaoEm(LAT_A_30M, LON_CENTRO); // criada, NÃO publicada

    MvcResult resultado =
        mockMvc
            .perform(
                get(PROXIMAS)
                    .param("lat", LAT_CENTRO)
                    .param("lon", LON_CENTRO)
                    .param("raioMetros", "20000")
                    .header("Authorization", bearer(ALICE_ID)))
            .andExpect(status().isOk())
            .andReturn();

    // Nem para a Alice, que é a criadora: o radar é uma visão pública por construção, e é isso que
    // torna o resultado independente de quem pergunta — premissa do cache compartilhado.
    assertThat(idsRetornados(resultado)).doesNotContain(rascunho);
  }

  @Test
  void filtro_por_categoria_restringe_o_resultado() throws Exception {
    UUID entrega = criarEPublicarEm(LAT_A_30M, LON_CENTRO, "ENTREGA", "0.00");
    // tokensRecompensa 0 é obrigatório aqui, e não desleixo de fixture: publicar TRIBO ou COLETA
    // com recompensa em tokens exige pote já financiado (a regra de conservação da moeda), o que
    // custaria tribo + carteira + financiamento com Idempotency-Key. Este teste é sobre o FILTRO
    // do radar por categoria; financiar pote não acrescentaria nada ao que ele mede.
    UUID tribo = criarEPublicarEm(LAT_A_300M, LON_CENTRO, "TRIBO", "0.00");

    MvcResult resultado =
        mockMvc
            .perform(
                get(PROXIMAS)
                    .param("lat", LAT_CENTRO)
                    .param("lon", LON_CENTRO)
                    .param("raioMetros", "500")
                    .param("categoria", "TRIBO")
                    .header("Authorization", bearer(ALICE_ID)))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(idsRetornados(resultado)).contains(tribo).doesNotContain(entrega);
  }

  @Test
  void raio_acima_do_maximo_responde_400_apontando_o_campo() throws Exception {
    MvcResult resultado =
        mockMvc
            .perform(
                get(PROXIMAS)
                    .param("lat", LAT_CENTRO)
                    .param("lon", LON_CENTRO)
                    .param("raioMetros", "20001")
                    .header("Authorization", bearer(ALICE_ID)))
            .andExpect(status().isBadRequest())
            .andReturn();

    assertThat(camposComErro(resultado)).contains("raioMetros");
  }

  @Test
  void latitude_fora_da_faixa_responde_400() throws Exception {
    MvcResult resultado =
        mockMvc
            .perform(
                get(PROXIMAS)
                    .param("lat", "91.0")
                    .param("lon", LON_CENTRO)
                    .header("Authorization", bearer(ALICE_ID)))
            .andExpect(status().isBadRequest())
            .andReturn();

    assertThat(camposComErro(resultado)).contains("lat");
  }

  @Test
  void coordenada_ausente_responde_400() throws Exception {
    mockMvc
        .perform(get(PROXIMAS).param("lon", LON_CENTRO).header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isBadRequest());
  }

  /**
   * Tentativa de injeção. O valor nem chega ao SQL: o binder falha ao converter para BigDecimal e o
   * request morre em 400. Se algum dia alguém trocar o tipo por String e concatenar, este teste
   * passa a receber 200 ou 500 e quebra.
   */
  @Test
  void latitude_nao_numerica_responde_400_e_nao_500() throws Exception {
    mockMvc
        .perform(
            get(PROXIMAS)
                .param("lat", "' OR 1=1--")
                .param("lon", LON_CENTRO)
                .header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void sem_token_responde_401() throws Exception {
    mockMvc
        .perform(get(PROXIMAS).param("lat", LAT_CENTRO).param("lon", LON_CENTRO))
        .andExpect(status().isUnauthorized());
  }

  // ─── Apoio ─────────────────────────────────────────────────────────────────────────────────

  private List<UUID> idsRetornados(MvcResult resultado) throws Exception {
    JsonNode raiz = JSON.readTree(resultado.getResponse().getContentAsString());
    List<UUID> ids = new ArrayList<>();
    for (JsonNode item : raiz) {
      ids.add(UUID.fromString(item.get("missao").get("id").asText()));
    }
    return ids;
  }

  private List<String> camposComErro(MvcResult resultado) throws Exception {
    JsonNode raiz = JSON.readTree(resultado.getResponse().getContentAsString());
    List<String> campos = new ArrayList<>();
    if (raiz.get("errors") != null) {
      for (JsonNode erro : raiz.get("errors")) {
        campos.add(erro.get("campo").asText());
      }
    }
    return campos;
  }

  private UUID criarEPublicarEm(String lat, String lon) throws Exception {
    return criarEPublicarEm(lat, lon, "ENTREGA", "0.00");
  }

  private UUID criarEPublicarEm(String lat, String lon, String categoria, String valorBrl)
      throws Exception {
    UUID missaoId = criarMissaoEm(lat, lon, categoria, valorBrl);

    // Publicar missão TRIBO/COLETA exige pote cobrindo a recompensa — e desde o ADR 0009 a
    // recompensa é calculada, então nenhuma missão vale 0 para escapar dessa guarda. O pote é
    // financiado por SQL de propósito: este teste é sobre o RADAR, e montar tribo, carteira e
    // financiamento com Idempotency-Key só para publicar acoplaria o radar à economia sem
    // acrescentar nada ao que ele mede. Mesmo recurso do salto de status usado na suíte de
    // carteira.
    jdbcTemplate.update("UPDATE missao SET pote_tokens = tokens_recompensa WHERE id = ?", missaoId);

    mockMvc
        .perform(post(BASE + "/{id}/publicar", missaoId).header("Authorization", bearer(ALICE_ID)))
        .andExpect(status().isOk());
    return missaoId;
  }

  private UUID criarMissaoEm(String lat, String lon) throws Exception {
    return criarMissaoEm(lat, lon, "ENTREGA", "0.00");
  }

  private UUID criarMissaoEm(String lat, String lon, String categoria, String valorBrl)
      throws Exception {
    MvcResult resultado =
        mockMvc
            .perform(
                post(BASE)
                    .header("Authorization", bearer(ALICE_ID))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo(lat, lon, categoria, valorBrl)))
            .andExpect(status().isCreated())
            .andReturn();

    UUID id =
        UUID.fromString(
            JSON.readTree(resultado.getResponse().getContentAsString()).get("id").asText());
    criadas.add(id);
    return id;
  }

  private static String corpo(String lat, String lon, String categoria, String valorBrl) {
    boolean carregaCoisa = "ENTREGA".equals(categoria) || "COLETA".equals(categoria);
    String insumos =
        carregaCoisa
            ? "\"pesoKg\": 10.00,\n          \"volumeL\": 40.00,"
            : "\"complexidade\": \"MEDIA\",";
    Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant fim = inicio.plus(2, ChronoUnit.DAYS);
    return """
        {
          "categoria": "%s",
          "titulo": "Missao de proximidade em Manaus",
          "descricao": "Fixture geografico distante do seed de Pinheiros.",
          "valorBrl": %s,
          %s
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
        .formatted(categoria, valorBrl, insumos, lat, lon, inicio, fim);
  }

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
