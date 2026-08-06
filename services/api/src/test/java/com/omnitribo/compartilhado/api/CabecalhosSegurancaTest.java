package com.omnitribo.compartilhado.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Cabeçalhos de segurança da resposta (item 8 da fase de segurança).
 *
 * <p>Estavam configurados em SecurityConfig e descritos na documentação, mas sem nenhuma assertion
 * — ou seja, alguém podia removê-los sem o build reclamar.
 */
@Import(JwtTestConfig.class)
class CabecalhosSegurancaTest extends TesteIntegracaoMvcBase {

  @Autowired MockMvc mockMvc;

  private static final String CSP_ESPERADA =
      "default-src 'none'; frame-ancestors 'none'; form-action 'none'";

  @Test
  void respostaDeSucesso_carregaOsCincoCabecalhosDeSeguranca() throws Exception {
    // .secure(true) é obrigatório: o HstsHeaderWriter do Spring Security só emite
    // Strict-Transport-Security quando request.isSecure(). Sem isso o header não apareceria e o
    // teste acusaria um bug de produção que não existe.
    MvcResult resultado =
        mockMvc
            .perform(get("/api/v1/ping").secure(true))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string("Content-Security-Policy", CSP_ESPERADA))
            .andReturn();

    // HSTS conferido por partes: o formato exato do separador varia entre versões do Spring
    // Security, mas max-age e includeSubDomains são o que a medida realmente garante.
    String hsts = resultado.getResponse().getHeader("Strict-Transport-Security");
    assertThat(hsts).isNotNull();
    assertThat(hsts).contains("max-age=31536000");
    assertThat(hsts).contains("includeSubDomains");
  }

  @Test
  void respostaDeErro401_tambemCarregaOsCabecalhos() throws Exception {
    // Prova que o HeaderWriterFilter roda ANTES do AuthenticationEntryPoint. É exatamente no
    // caminho de erro que configuração mal feita costuma vazar resposta sem proteção — e uma
    // página de erro sem X-Frame-Options continua sendo clickjackable.
    MvcResult resultado =
        mockMvc
            .perform(get("/api/v1/auth/me").secure(true))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string("Content-Security-Policy", CSP_ESPERADA))
            .andReturn();

    assertThat(resultado.getResponse().getHeader("Strict-Transport-Security"))
        .contains("max-age=31536000");
  }
}
