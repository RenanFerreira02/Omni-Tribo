package com.omnitribo.logistica.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Contrato HTTP de {@code POST /api/v1/logistica/previsao-falha}.
 *
 * <p>Cobre o que a especificação exige do endpoint: formato do retorno, característica fora de
 * faixa virando 400, determinismo entre chamadas, e as duas sanidades — um caso claramente
 * arriscado e um claramente tranquilo.
 */
@Import(JwtTestConfig.class)
class PrevisaoFalhaControllerTest extends TesteIntegracaoMvcBase {

  private static final UUID ALICE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
  private static final String URL = "/api/v1/logistica/previsao-falha";

  /** Sábado às 19h, comercial, CEP da pior faixa, 2 tentativas, chuva forte. */
  private static final String CORPO_ALTO_RISCO =
      """
      { "janelaHoraInicio": 19, "diaSemana": "SATURDAY", "tipoEndereco": "COMERCIAL",
        "cep": "08010000", "pesoKg": 25.00, "volumeL": 200.00,
        "tentativasAnteriores": 2, "chuvaMm": 18.0, "temperaturaC": 17.0 }
      """;

  /** Terça às 10h, residencial, CEP da melhor faixa, primeira tentativa, tempo bom. */
  private static final String CORPO_BAIXO_RISCO =
      """
      { "janelaHoraInicio": 10, "diaSemana": "TUESDAY", "tipoEndereco": "RESIDENCIAL",
        "cep": "05400000", "pesoKg": 1.50, "volumeL": 8.00,
        "tentativasAnteriores": 0, "chuvaMm": 0.0, "temperaturaC": 24.0 }
      """;

  @Autowired MockMvc mockMvc;

  // ─────────────────────────────── Caminho feliz ───────────────────────────────

  @Test
  void previsao_devolve_probabilidade_faixa_e_fatores() throws Exception {
    mockMvc
        .perform(prever(CORPO_ALTO_RISCO))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.probabilidadeFalha").isNumber())
        .andExpect(jsonPath("$.faixaRisco").value("ALTO"))
        .andExpect(jsonPath("$.multiplicadorRecompensa").isNumber())
        .andExpect(jsonPath("$.versaoModelo").isNumber())
        .andExpect(jsonPath("$.fatoresPrincipais", Matchers.hasSize(3)))
        // A explicação é o produto, tanto quanto o número: cada fator precisa ser legível.
        .andExpect(jsonPath("$.fatoresPrincipais[0].caracteristica").isNotEmpty())
        .andExpect(jsonPath("$.fatoresPrincipais[0].rotulo").isNotEmpty())
        .andExpect(jsonPath("$.fatoresPrincipais[0].contribuicao").isNumber())
        .andExpect(jsonPath("$.fatoresPrincipais[0].direcao", Matchers.oneOf("AUMENTA", "REDUZ")))
        .andExpect(jsonPath("$.fatoresPrincipais[0].pesoRelativo").isNumber())
        .andExpect(jsonPath("$.fatoresPrincipais[0].valorObservado").isNotEmpty())
        .andExpect(jsonPath("$.featuresImputadas", Matchers.hasSize(0)));
  }

  @Test
  void caso_claramente_tranquilo_recebe_faixa_baixa() throws Exception {
    mockMvc
        .perform(prever(CORPO_BAIXO_RISCO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.faixaRisco").value("BAIXO"))
        .andExpect(jsonPath("$.multiplicadorRecompensa").value(Matchers.lessThan(1.2)));
  }

  @Test
  void caso_arriscado_pontua_acima_do_tranquilo_e_paga_mais() throws Exception {
    double alto = probabilidadeDe(CORPO_ALTO_RISCO);
    double baixo = probabilidadeDe(CORPO_BAIXO_RISCO);

    assertThat(alto).isGreaterThan(baixo);
    assertThat(multiplicadorDe(CORPO_ALTO_RISCO)).isGreaterThan(multiplicadorDe(CORPO_BAIXO_RISCO));
  }

  @Test
  void clima_ausente_e_imputado_e_a_resposta_diz_isso() throws Exception {
    String semClima =
        """
        { "janelaHoraInicio": 19, "diaSemana": "SATURDAY", "tipoEndereco": "COMERCIAL",
          "cep": "08010000", "pesoKg": 25.00, "volumeL": 200.00, "tentativasAnteriores": 2 }
        """;

    mockMvc
        .perform(prever(semClima))
        .andExpect(status().isOk())
        // Esconder a imputação seria desonesto: o score se apoiou em suposição, e quem lê precisa
        // saber disso para calibrar o quanto confia nele.
        .andExpect(
            jsonPath(
                "$.featuresImputadas", Matchers.containsInAnyOrder("CHUVA_MM", "TEMPERATURA_C")));
  }

  // ─────────────────────────────── Determinismo ───────────────────────────────

  @Test
  void duas_chamadas_identicas_devolvem_exatamente_o_mesmo_corpo() throws Exception {
    String primeira = corpoDe(CORPO_ALTO_RISCO);
    String segunda = corpoDe(CORPO_ALTO_RISCO);

    // Determinismo é requisito e não consequência: o multiplicador derivado daqui é CONGELADO na
    // missão, e duas leituras divergentes tornariam impossível auditar um crédito depois.
    assertThat(primeira).isEqualTo(segunda);
  }

  // ─────────────────────────────── Erro ───────────────────────────────

  @Test
  void hora_fora_de_faixa_responde_400_apontando_o_campo() throws Exception {
    String horaInvalida =
        CORPO_ALTO_RISCO.replace("\"janelaHoraInicio\": 19", "\"janelaHoraInicio\": 25");

    mockMvc
        .perform(prever(horaInvalida))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/requisicao-invalida"))
        .andExpect(jsonPath("$.errors[?(@.campo == 'janelaHoraInicio')]", Matchers.hasSize(1)));
  }

  @Test
  void peso_negativo_responde_400() throws Exception {
    mockMvc
        .perform(prever(CORPO_ALTO_RISCO.replace("\"pesoKg\": 25.00", "\"pesoKg\": -1.00")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[?(@.campo == 'pesoKg')]", Matchers.hasSize(1)));
  }

  @Test
  void tentativas_negativas_respondem_400() throws Exception {
    mockMvc
        .perform(
            prever(
                CORPO_ALTO_RISCO.replace(
                    "\"tentativasAnteriores\": 2", "\"tentativasAnteriores\": -3")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[?(@.campo == 'tentativasAnteriores')]", Matchers.hasSize(1)));
  }

  @Test
  void cep_malformado_responde_400() throws Exception {
    mockMvc
        .perform(prever(CORPO_ALTO_RISCO.replace("\"cep\": \"08010000\"", "\"cep\": \"080-10\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[?(@.campo == 'cep')]", Matchers.hasSize(1)));
  }

  @Test
  void tipo_de_endereco_desconhecido_responde_400() throws Exception {
    mockMvc
        .perform(prever(CORPO_ALTO_RISCO.replace("\"COMERCIAL\"", "\"GALPAO\"")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void campo_obrigatorio_ausente_responde_400() throws Exception {
    mockMvc
        .perform(prever("{ \"janelaHoraInicio\": 19 }"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors", Matchers.not(Matchers.empty())));
  }

  @Test
  void sem_jwt_responde_401() throws Exception {
    mockMvc
        .perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(CORPO_ALTO_RISCO))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/nao-autenticado"));
  }

  // ─────────────────────────────── Auxiliares ───────────────────────────────

  private MockHttpServletRequestBuilder prever(String corpo) {
    return post(URL)
        .header(
            "Authorization",
            "Bearer " + JwtTestConfig.gerarTokenValido(ALICE_ID, "alice@omnitribo.dev", "USUARIO"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(corpo);
  }

  private String corpoDe(String requisicao) throws Exception {
    MvcResult resultado =
        mockMvc.perform(prever(requisicao)).andExpect(status().isOk()).andReturn();
    return resultado.getResponse().getContentAsString();
  }

  private double probabilidadeDe(String requisicao) throws Exception {
    return JSON.readTree(corpoDe(requisicao)).get("probabilidadeFalha").asDouble();
  }

  private double multiplicadorDe(String requisicao) throws Exception {
    return JSON.readTree(corpoDe(requisicao)).get("multiplicadorRecompensa").asDouble();
  }
}
