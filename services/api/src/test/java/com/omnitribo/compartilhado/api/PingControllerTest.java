package com.omnitribo.compartilhado.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnitribo.TesteIntegracaoBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@AutoConfigureTestRestTemplate
class PingControllerTest extends TesteIntegracaoBase {

  @Autowired TestRestTemplate rest;

  @Test
  void ping_retorna_pong() {
    ResponseEntity<String> resp = rest.getForEntity("/api/v1/ping", String.class);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).contains("\"mensagem\":\"pong\"");
  }

  @Test
  void ping_devolve_correlation_id_no_header() {
    ResponseEntity<String> resp = rest.getForEntity("/api/v1/ping", String.class);

    assertThat(resp.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
  }
}
