package com.omnitribo.logistica.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Pontos de custódia — primeiro caminho de leitura do módulo logistica.
 *
 * <p>O ponto de Pinheiros do seed fica em (-23.5640, -46.6934), com apelido "Leroy Merlin
 * Pinheiros". É o UUID que {@code MissaoResponse.pontoCustodiaId} expunha cru e que o app exibia.
 */
@Import(JwtTestConfig.class)
class PontoCustodiaControllerTest extends TesteIntegracaoMvcBase {

  private static final UUID ALICE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
  private static final UUID LEROY_PINHEIROS =
      UUID.fromString("cccccccc-0000-0000-0000-000000000001");

  private static final String BASE = "/api/v1/pontos-custodia";
  private static final String LAT_PINHEIROS = "-23.5640";
  private static final String LON_PINHEIROS = "-46.6934";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  @AfterEach
  void reativarPontos() {
    jdbcTemplate.update("UPDATE ponto_custodia SET ativo = TRUE WHERE ativo = FALSE");
  }

  @Test
  void detalhe_resolve_o_uuid_em_um_nome_legivel() throws Exception {
    mockMvc
        .perform(autenticado(get(BASE + "/" + LEROY_PINHEIROS)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.apelido").value("Leroy Merlin Pinheiros"))
        .andExpect(jsonPath("$.codigo").value("LM-PIN-001"))
        .andExpect(jsonPath("$.tipo").value("LOJA"))
        .andExpect(jsonPath("$.lat").exists())
        .andExpect(jsonPath("$.lon").exists())
        // Sem coordenada de referência não há o que medir contra.
        .andExpect(jsonPath("$.distanciaM").doesNotExist());
  }

  /**
   * Inativo responde 404, e não um corpo com {@code ativo: false}. Uma missão antiga pode apontar
   * para um ponto desativado, e devolvê-lo com uma flag que a tela pode esquecer de ler levaria o
   * executor até uma loja que não recebe mais encomenda.
   */
  @Test
  void ponto_inativo_responde_404() throws Exception {
    jdbcTemplate.update("UPDATE ponto_custodia SET ativo = FALSE WHERE id = ?", LEROY_PINHEIROS);

    mockMvc
        .perform(autenticado(get(BASE + "/" + LEROY_PINHEIROS)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/nao-encontrado"));
  }

  @Test
  void busca_por_raio_ordena_por_distancia_medida_no_servidor() throws Exception {
    mockMvc
        .perform(
            autenticado(
                get(BASE)
                    .param("lat", LAT_PINHEIROS)
                    .param("lon", LON_PINHEIROS)
                    .param("raioMetros", "20000")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].apelido").value("Leroy Merlin Pinheiros"))
        // Distância medida pelo PostGIS: o cliente nunca a informa. No próprio ponto, ~0 m.
        .andExpect(jsonPath("$[0].distanciaM").value(org.hamcrest.Matchers.lessThan(1.0)))
        .andExpect(jsonPath("$[1].distanciaM").value(org.hamcrest.Matchers.greaterThan(0.0)));
  }

  @Test
  void busca_por_raio_omite_pontos_inativos() throws Exception {
    jdbcTemplate.update("UPDATE ponto_custodia SET ativo = FALSE WHERE id = ?", LEROY_PINHEIROS);

    mockMvc
        .perform(
            autenticado(
                get(BASE)
                    .param("lat", LAT_PINHEIROS)
                    .param("lon", LON_PINHEIROS)
                    .param("raioMetros", "20000")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.codigo == 'LM-PIN-001')]").value(org.hamcrest.Matchers.empty()));
  }

  @Test
  void raio_minusculo_devolve_lista_vazia_e_nao_erro() throws Exception {
    mockMvc
        .perform(
            autenticado(
                get(BASE)
                    .param("lat", "-3.1181")
                    .param("lon", "-60.0217")
                    .param("raioMetros", "10")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void raio_acima_do_teto_responde_400() throws Exception {
    // Mesmo teto de /missoes/proximas: o app não pode ter duas regras de "até onde dá para pedir".
    mockMvc
        .perform(
            autenticado(
                get(BASE)
                    .param("lat", LAT_PINHEIROS)
                    .param("lon", LON_PINHEIROS)
                    .param("raioMetros", "20001")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void busca_sem_coordenada_responde_400() throws Exception {
    mockMvc.perform(autenticado(get(BASE))).andExpect(status().isBadRequest());
  }

  @Test
  void sem_token_responde_401() throws Exception {
    mockMvc.perform(get(BASE + "/" + LEROY_PINHEIROS)).andExpect(status().isUnauthorized());
  }

  private MockHttpServletRequestBuilder autenticado(MockHttpServletRequestBuilder builder) {
    return builder.header(
        "Authorization",
        "Bearer " + JwtTestConfig.gerarTokenValido(ALICE_ID, "alice@omnitribo.dev", "USUARIO"));
  }
}
