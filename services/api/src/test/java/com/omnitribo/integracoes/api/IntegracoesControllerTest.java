package com.omnitribo.integracoes.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import com.omnitribo.integracoes.FontesExternasFalsasConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Contrato HTTP de clima e CEP.
 *
 * <p>O que importa aqui não é o valor devolvido — o teste do cliente já cobriu o mapeamento —, e
 * sim que as TRÊS situações do provedor cheguem ao app com {@code type} diferente: sucesso, CEP que
 * não existe (404) e provedor fora do ar (503). São três reações de UI distintas, e o app decide
 * por esse campo. Ver ADR 0010 e ADR 0011.
 */
@Import({JwtTestConfig.class, FontesExternasFalsasConfig.class})
class IntegracoesControllerTest extends TesteIntegracaoMvcBase {

  private static final UUID ALICE_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

  @Autowired MockMvc mockMvc;

  // ─── Clima ─────────────────────────────────────────────────────────────────────────────────

  @Test
  void clima_devolve_a_condicao_atual() throws Exception {
    mockMvc
        .perform(
            autenticado(get("/api/v1/clima").param("lat", "-23.5629").param("lon", "-46.6996")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.temperaturaC").value(21.3))
        .andExpect(jsonPath("$.codigo").value(61))
        .andExpect(jsonPath("$.descricao").value("Chuva"));
  }

  /**
   * 503 com type próprio, e não 500. A diferença é o que o app faz: 500 pede "reporte o erro"; este
   * pede "esconda o card e siga". Um card de clima não pode parecer uma falha do produto.
   */
  @Test
  void provedor_de_clima_fora_do_ar_responde_503_com_type_proprio() throws Exception {
    mockMvc
        .perform(autenticado(get("/api/v1/clima").param("lat", "89.9").param("lon", "-46.6996")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(
            jsonPath("$.type")
                .value("https://omnitribo.dev/problemas/servico-externo-indisponivel"))
        .andExpect(jsonPath("$.traceId").exists());
  }

  @Test
  void clima_sem_coordenada_responde_400() throws Exception {
    mockMvc
        .perform(autenticado(get("/api/v1/clima")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/requisicao-invalida"));
  }

  @Test
  void clima_com_latitude_absurda_responde_400_sem_chamar_o_provedor() throws Exception {
    mockMvc
        .perform(autenticado(get("/api/v1/clima").param("lat", "999").param("lon", "-46.69")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void clima_sem_token_responde_401() throws Exception {
    // Anônimo transformaria o endpoint num proxy aberto que qualquer um usa em nosso nome.
    mockMvc
        .perform(get("/api/v1/clima").param("lat", "-23.56").param("lon", "-46.69"))
        .andExpect(status().isUnauthorized());
  }

  // ─── CEP ───────────────────────────────────────────────────────────────────────────────────

  @Test
  void cep_valido_devolve_endereco_no_vocabulario_do_nosso_dominio() throws Exception {
    mockMvc
        .perform(autenticado(get("/api/v1/enderecos/01001000")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cep").value("01001000"))
        // "cidade", e não "localidade" do provedor: a tradução acontece na borda para que trocar de
        // provedor não obrigue o app a mudar.
        .andExpect(jsonPath("$.cidade").value("São Paulo"))
        .andExpect(jsonPath("$.uf").value("SP"));
  }

  @Test
  void cep_inexistente_responde_404_e_nao_503() throws Exception {
    // As duas causas pedem reações OPOSTAS: corrigir o número, ou tentar de novo mais tarde.
    mockMvc
        .perform(autenticado(get("/api/v1/enderecos/99999999")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/nao-encontrado"));
  }

  @Test
  void provedor_de_cep_fora_do_ar_responde_503_e_nao_404() throws Exception {
    mockMvc
        .perform(autenticado(get("/api/v1/enderecos/00000000")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(
            jsonPath("$.type")
                .value("https://omnitribo.dev/problemas/servico-externo-indisponivel"));
  }

  @Test
  void cep_malformado_responde_400_sem_chamar_o_provedor() throws Exception {
    mockMvc
        .perform(autenticado(get("/api/v1/enderecos/1234-567")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://omnitribo.dev/problemas/requisicao-invalida"));
  }

  private MockHttpServletRequestBuilder autenticado(MockHttpServletRequestBuilder builder) {
    return builder.header(
        "Authorization",
        "Bearer " + JwtTestConfig.gerarTokenValido(ALICE_ID, "alice@teste.dev", "USUARIO"));
  }
}
