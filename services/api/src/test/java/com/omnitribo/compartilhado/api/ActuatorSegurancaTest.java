package com.omnitribo.compartilhado.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnitribo.JwtTestConfig;
import com.omnitribo.TesteIntegracaoBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Superfície do Actuator na porta de gestão.
 *
 * <p>Precisa de porta real (RANDOM_PORT), e não MockMvc: a separação entre porta pública e porta de
 * gestão só existe quando há servidor de verdade — em WebEnvironment.MOCK as duas colapsam numa
 * cadeia só e o teste não mediria nada.
 *
 * <p>Existe porque {@code GET /actuator/health} respondia <b>401</b>: o {@code
 * anyRequest().authenticated()} da cadeia principal alcançava a porta de gestão. Um health check
 * que exige JWT não é um health check, e o {@code show-details: when-authorized} ficava sem sentido
 * por não haver caminho anônimo para diferenciar.
 */
@Import(JwtTestConfig.class)
@AutoConfigureTestRestTemplate
class ActuatorSegurancaTest extends TesteIntegracaoBase {

  @LocalManagementPort int portaGestao;

  @Autowired TestRestTemplate rest;

  private String url(String caminho) {
    return "http://localhost:" + portaGestao + caminho;
  }

  @Test
  void health_respondeSemAutenticacao() {
    ResponseEntity<String> resposta = rest.getForEntity(url("/actuator/health"), String.class);

    assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resposta.getBody()).contains("\"status\":\"UP\"");
  }

  @Test
  void info_respondeSemAutenticacao() {
    assertThat(rest.getForEntity(url("/actuator/info"), String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  void metrics_continuaExigindoAutenticacao() {
    // O ponto do teste é que a liberação foi CIRÚRGICA. Abrir toda a cadeia do actuator seria uma
    // regressão silenciosa: metrics entrega nomes de endpoint, contadores de uso e tamanho de pool
    // — reconhecimento barato para quem está sondando o serviço.
    assertThat(rest.getForEntity(url("/actuator/metrics"), String.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
