package com.omnitribo.identidade.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Tribos, e o centro geográfico derivado por PostGIS. */
@Import(JwtTestConfig.class)
class TriboControllerTest extends TesteIntegracaoMvcBase {

  private static final UUID ALICE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
  private static final UUID TRIBO_PINHEIROS =
      UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

  private static final String BASE = "/api/v1/tribos";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  void lista_em_ordem_alfabetica_e_sem_envelope_de_pagina() throws Exception {
    mockMvc
        .perform(autenticado(get(BASE)))
        .andExpect(status().isOk())
        // Array puro: uma tribo é um bairro, e paginar isso faria a tela de registro ter
        // "carregar mais" para escolher onde a pessoa mora.
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].nome").value("Tribo Jardim América"))
        .andExpect(jsonPath("$[1].nome").value("Tribo Pinheiros"))
        .andExpect(jsonPath("$[2].nome").value("Tribo Vila Madalena"));
  }

  /** N+1 evitado de propósito: o centro custa uma consulta PostGIS por tribo. */
  @Test
  void lista_nao_calcula_o_centro_de_cada_tribo() throws Exception {
    mockMvc
        .perform(autenticado(get(BASE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].centroLat").doesNotExist())
        .andExpect(jsonPath("$[1].centroLat").doesNotExist());
  }

  @Test
  void detalhe_traz_o_centro_derivado_das_missoes_e_pontos_da_tribo() throws Exception {
    mockMvc
        .perform(autenticado(get(BASE + "/" + TRIBO_PINHEIROS)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nome").value("Tribo Pinheiros"))
        .andExpect(jsonPath("$.bairro").value("Pinheiros"))
        // Pinheiros tem ponto de custódia e missões no seed, então há centro. O valor exato depende
        // do seed; o que o contrato garante é que existe e cai na região de São Paulo.
        .andExpect(jsonPath("$.centroLat").exists())
        .andExpect(jsonPath("$.centroLon").exists())
        .andExpect(jsonPath("$.centroLat").value(org.hamcrest.Matchers.lessThan(-23.0)))
        .andExpect(jsonPath("$.centroLon").value(org.hamcrest.Matchers.lessThan(-46.0)));
  }

  /**
   * Tribo sem missão e sem ponto de custódia não tem centro — e devolver um inventado faria o mapa
   * saltar para o meio do oceano. Nulo é a resposta honesta; o app fica onde estava.
   */
  @Test
  void tribo_sem_missao_nem_ponto_devolve_centro_nulo() throws Exception {
    UUID vazia = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO tribo (id, nome, bairro, criada_em) VALUES (?, ?, ?, NOW())",
        vazia,
        "Tribo Sem Nada",
        "Bairro Vazio");
    try {
      mockMvc
          .perform(autenticado(get(BASE + "/" + vazia)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.centroLat").doesNotExist())
          .andExpect(jsonPath("$.centroLon").doesNotExist());
    } finally {
      jdbcTemplate.update("DELETE FROM tribo WHERE id = ?", vazia);
    }
  }

  @Test
  void tribo_inexistente_responde_404() throws Exception {
    mockMvc
        .perform(autenticado(get(BASE + "/" + UUID.randomUUID())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/nao-encontrado"));
  }

  @Test
  void sem_token_responde_401() throws Exception {
    mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
  }

  private MockHttpServletRequestBuilder autenticado(MockHttpServletRequestBuilder builder) {
    return builder.header(
        "Authorization",
        "Bearer " + JwtTestConfig.gerarTokenValido(ALICE_ID, "alice@omnitribo.dev", "USUARIO"));
  }
}
