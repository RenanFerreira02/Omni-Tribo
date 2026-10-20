package com.omnitribo.missoes.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import com.omnitribo.missoes.PoteDeTeste;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * O teto de acurácia vem da configuração, e a recusa CITA o teto que de fato valeu (ADR 0040).
 *
 * <p>É o profile {@code feira} em miniatura: 150 m. O mesmo corpo de 120 m que {@code
 * CheckinControllerTest} recusaria com o padrão de 50 m passa aqui — e acima de 150 a resposta
 * publica 150, não a constante, senão o app diria ao usuário um número que o servidor não aplica.
 */
@Import(JwtTestConfig.class)
// Contexto próprio por causa da propriedade, e contexto custa um pool inteiro de conexões (ver
// ContainerConfig): pool pequeno e fechado ao fim da classe, para não esgotar o max_connections
// do contêiner que as outras classes compartilham.
@TestPropertySource(
    properties = {
      "app.geolocalizacao.acuracia-maxima-m=150",
      "spring.datasource.hikari.maximum-pool-size=4"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CheckinTetoConfiguradoTest extends TesteIntegracaoMvcBase {

  private static final UUID ALICE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
  private static final UUID ERIK_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000006");
  private static final String BASE = "/api/v1/missoes";
  private static final String LAT = "-3.1181";
  private static final String LON = "-60.0217";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  private final List<UUID> missoesCriadas = new ArrayList<>();

  @BeforeEach
  void limparCheckinsDoExecutor() {
    jdbcTemplate.update("DELETE FROM checkin WHERE usuario_id = ?", ERIK_ID);
  }

  @AfterEach
  void limparMissoes() {
    for (UUID id : missoesCriadas) {
      jdbcTemplate.update("DELETE FROM checkin WHERE missao_id = ?", id);
      jdbcTemplate.update("DELETE FROM missao_evento WHERE missao_id = ?", id);
      jdbcTemplate.update("DELETE FROM missao WHERE id = ?", id);
    }
    missoesCriadas.clear();
  }

  @Test
  void precisao_de_120_metros_passa_com_teto_de_150() throws Exception {
    UUID missaoId = missaoEmAndamento();

    mockMvc
        .perform(checkin(missaoId, "120", "teto-150-aceita"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AGUARDANDO_CONFIRMACAO"));
  }

  @Test
  void acima_do_teto_a_recusa_publica_o_teto_configurado() throws Exception {
    UUID missaoId = missaoEmAndamento();

    mockMvc
        .perform(checkin(missaoId, "160", "teto-150-recusa"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value("https://omnitribo.dev/problemas/checkin-acuracia-insuficiente"))
        .andExpect(jsonPath("$.acuraciaMaximaM").value(150));
  }

  private org.springframework.test.web.servlet.RequestBuilder checkin(
      UUID missaoId, String acuracia, String chave) {
    return post(BASE + "/{id}/checkin", missaoId)
        .header("Authorization", bearer(ERIK_ID))
        .header("Idempotency-Key", chave + "-" + missaoId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            "{\"lat\": %s, \"lon\": %s, \"acuraciaM\": %s, \"mocked\": false}"
                .formatted(LAT, LON, acuracia));
  }

  private UUID missaoEmAndamento() throws Exception {
    Instant inicio = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String corpo =
        """
        {
          "categoria": "ENTREGA",
          "titulo": "Missão para o teto de acurácia",
          "descricao": "Fixture do teste do teto de acurácia configurável.",
          "valorBrl": 0.00,
          "pesoKg": 5.00,
          "volumeL": 20.00,
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
            .formatted(LAT, LON, inicio, inicio.plus(2, ChronoUnit.DAYS));

    MvcResult criada =
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
            JSON.readTree(criada.getResponse().getContentAsString()).get("id").asText());
    missoesCriadas.add(id);

    PoteDeTeste.cobrir(jdbcTemplate, id);
    for (String acao : List.of("publicar", "aceitar", "iniciar")) {
      UUID ator = "publicar".equals(acao) ? ALICE_ID : ERIK_ID;
      mockMvc
          .perform(post(BASE + "/{id}/" + acao, id).header("Authorization", bearer(ator)))
          .andExpect(status().isOk());
    }
    return id;
  }

  private String bearer(UUID usuarioId) {
    return "Bearer "
        + JwtTestConfig.gerarTokenValido(usuarioId, usuarioId + "@teste.dev", "USUARIO");
  }
}
