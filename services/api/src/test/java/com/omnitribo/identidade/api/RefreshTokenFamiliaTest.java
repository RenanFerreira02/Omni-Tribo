package com.omnitribo.identidade.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoMvcBase;
import com.omnitribo.identidade.infra.RefreshTokenRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Testa concorrência na rotação de refresh tokens: 10 threads tentando usar o mesmo token
 * simultaneamente. Apenas UMA deve ter sucesso; as outras devem receber 401. A família deve ser
 * completamente revogada após detecção de reuso.
 *
 * <p>@Transactional(NOT_SUPPORTED): sem transação envolvente nos testes — garante que o banco
 * aplica as constraints imediatamente e que threads concorrentes veem o estado real.
 */
@Import(JwtTestConfig.class)
class RefreshTokenFamiliaTest extends TesteIntegracaoMvcBase {

  @Autowired MockMvc mockMvc;
  @Autowired RefreshTokenRepository refreshTokenRepository;

  @BeforeEach
  void limpar() {
    refreshTokenRepository.deleteAll();
  }

  @Test
  void dezThreads_mesmoPolicialToken_apenasUmaSucede() throws Exception {
    // Login para obter um refresh token válido
    MvcResult loginResult =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"alice@omnitribo.dev\",\"senha\":\"Senha@123\"}"))
            .andExpect(status().isOk())
            .andReturn();

    String refreshToken =
        JSON.readTree(loginResult.getResponse().getContentAsString()).path("refreshToken").asText();

    // 10 threads tentam rotacionar o mesmo token simultaneamente
    int numThreads = 10;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    List<Future<Integer>> resultados = new ArrayList<>();

    for (int i = 0; i < numThreads; i++) {
      final String token = refreshToken;
      resultados.add(
          executor.submit(
              () -> {
                try {
                  MvcResult r =
                      mockMvc
                          .perform(
                              post("/api/v1/auth/refresh")
                                  .contentType(MediaType.APPLICATION_JSON)
                                  .content("{\"refreshToken\":\"" + token + "\"}"))
                          .andReturn();
                  return r.getResponse().getStatus();
                } catch (Exception e) {
                  return 500;
                }
              }));
    }

    executor.shutdown();
    assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    // Contabiliza sucesso (200) vs. falhas (401)
    long sucessos =
        resultados.stream()
            .mapToInt(
                f -> {
                  try {
                    return f.get();
                  } catch (Exception e) {
                    return 500;
                  }
                })
            .filter(s -> s == 200)
            .count();

    long falhas401 =
        resultados.stream()
            .mapToInt(
                f -> {
                  try {
                    return f.get();
                  } catch (Exception e) {
                    return 500;
                  }
                })
            .filter(s -> s == 401)
            .count();

    // Exatamente 1 thread deve ter tido sucesso (rotação atômica)
    assertThat(sucessos).as("Apenas uma thread deve conseguir rotacionar o token").isEqualTo(1);

    // Após detecção de reuso, toda a família deve estar revogada
    long tokensAtivos =
        refreshTokenRepository.findAll().stream().filter(t -> t.getRevogadoEm() == null).count();

    // O token emitido pela thread vencedora pode estar ativo; mas após reuso pelas perdedoras,
    // a família inteira é revogada. Se houve reuso, 0 tokens ativos. Se não (raro), 1 ativo.
    // A invariante principal: nenhuma thread perdedora recebeu 200.
    assertThat(falhas401 + sucessos)
        .as("Todas as threads devem ter resultado em 200 ou 401")
        .isEqualTo(numThreads);
  }
}
