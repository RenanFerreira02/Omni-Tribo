package com.omnitribo.identidade.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * POST /auth/registrar deve estar sujeito ao rate limit de escrita.
 *
 * <p>Antes, o RateLimitFilter isentava o prefixo /api/v1/auth/ inteiro e o AutenticacaoService não
 * chamava o BloqueioLoginService no registro: a rota não tinha limite algum. Como cada chamada
 * executa um hash Argon2id (16 MB e ~100 ms de CPU), era um amplificador de DoS não autenticado.
 *
 * <p>O limite é apertado por @TestPropertySource porque o perfil de teste usa 10000/min para não
 * mascarar outros testes com 429. Apertar em escopo de teste é o oposto de relaxar produção — o
 * valor real continua sendo o do application.yml.
 */
@Import(JwtTestConfig.class)
@TestPropertySource(properties = "app.rate-limit.escrita-por-minuto=3")
class RegistroRateLimitTest extends TesteIntegracaoMvcBase {

  @Autowired MockMvc mockMvc;

  @Test
  void registro_acimaDoLimite_retorna429ComRetryAfter() throws Exception {
    // IP próprio: o bucket do RateLimitFilter é por IP quando não há token, e outras classes de
    // teste compartilham o mapa na mesma JVM.
    String ip =
        "198.51.100." + (int) (Math.abs(UUID.randomUUID().getLeastSignificantBits() % 254) + 1);

    for (int i = 0; i < 3; i++) {
      mockMvc
          .perform(
              post("/api/v1/auth/registrar")
                  .with(vindoDe(ip))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(corpoRegistro()))
          .andExpect(status().isCreated());
    }

    mockMvc
        .perform(
            post("/api/v1/auth/registrar")
                .with(vindoDe(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoRegistro()))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
  }

  private static String corpoRegistro() {
    String sufixo = UUID.randomUUID().toString().substring(0, 8);
    return """
        {
          "nome": "Registro %s",
          "email": "registro%s@omnitribo.dev",
          "handle": "reg%s",
          "senha": "SenhaLongaDeTeste@2026"
        }
        """
        .formatted(sufixo, sufixo, sufixo);
  }
}
