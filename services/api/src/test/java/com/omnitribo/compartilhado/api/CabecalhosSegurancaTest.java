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

  /**
   * O Swagger UI precisa de uma CSP que a API não pode ter.
   *
   * <p>Regressão concreta que este teste tranca: com o {@code default-src 'none'} da cadeia
   * principal alcançando {@code /swagger-ui/**}, o browser recusava o bundle e a página abria EM
   * BRANCO — com 200 no HTML e nada de errado no log do servidor. Nenhum teste acusava.
   *
   * <p><b>Sob o perfil de teste o springdoc está desligado</b> ({@code application-test.yml}),
   * então a resposta é 404. Não é limitação: o que se mede aqui é o header, escrito pelo {@code
   * HeaderWriterFilter} antes de qualquer handler — o mesmo caminho de erro que o teste do 401
   * acima já usa para provar que cabeçalho não vaza em resposta de falha.
   */
  @Test
  void swaggerUi_recebeCspQuePermiteRenderizarASpa() throws Exception {
    MvcResult resultado = mockMvc.perform(get("/swagger-ui/index.html").secure(true)).andReturn();

    String csp = resultado.getResponse().getHeader("Content-Security-Policy");
    assertThat(csp).isNotNull();
    assertThat(csp).contains("script-src 'self' 'unsafe-inline'");
    assertThat(csp).contains("style-src 'self' 'unsafe-inline'");
    // connect-src é o que libera o fetch de /v3/api-docs. Sem ele a UI renderiza a moldura e nunca
    // carrega endpoint nenhum — falha mais sutil que a página em branco, e igualmente inútil.
    assertThat(csp).contains("connect-src 'self'");
    assertThat(csp).doesNotContain("default-src 'none'");
    // Relaxar a CSP não pode significar abrir mão do que não atrapalha a UI.
    assertThat(csp).contains("frame-ancestors 'none'");
  }

  @Test
  void schemaOpenApi_tambemRecebeACspDoSwagger() throws Exception {
    // /v3/api-docs é servido por uma cadeia diferente de /api/v1/**. Sem esta assertion, alguém
    // pode restringir o securityMatcher só a /swagger-ui/** e quebrar o carregamento da UI.
    MvcResult resultado = mockMvc.perform(get("/v3/api-docs").secure(true)).andReturn();

    assertThat(resultado.getResponse().getHeader("Content-Security-Policy"))
        .contains("script-src 'self' 'unsafe-inline'");
  }

  @Test
  void api_permaneceComCspEstritaDepoisDaExcecaoDoSwagger() throws Exception {
    // O contrapeso do teste acima. O jeito errado de consertar a página em branco é relaxar a CSP
    // global; se alguém fizer isso, é aqui que o build fica vermelho.
    mockMvc
        .perform(get("/api/v1/ping").secure(true))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Security-Policy", CSP_ESPERADA));
  }
}
