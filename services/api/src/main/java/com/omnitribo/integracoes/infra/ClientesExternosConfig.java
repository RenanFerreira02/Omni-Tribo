package com.omnitribo.integracoes.infra;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builder compartilhado pelos clientes externos, com TIMEOUT curto.
 *
 * <p><b>O timeout é a peça central, não um detalhe de configuração.</b> Sem ele, o default é
 * esperar indefinidamente: um provedor que aceita a conexão e nunca responde prenderia a thread da
 * requisição, e sob carga isso esgota o pool do servlet — o card de clima derrubaria o login. Dois
 * segundos são generosos para uma consulta que existe para enfeitar um mapa.
 *
 * <p>O timeout mora AQUI, na configuração, e não dentro de cada cliente, por uma razão de teste:
 * {@code MockRestServiceServer.bindTo(builder)} instala a própria {@code requestFactory}, e um
 * cliente que sobrescrevesse a fábrica no construtor apagaria o mock — o teste passaria a bater na
 * internet de verdade sem avisar.
 *
 * <p>Este bean SUBSTITUI o {@code RestClient.Builder} autoconfigurado pelo Boot (que é
 * {@code @ConditionalOnMissingBean}). É intencional e seguro hoje: estes dois clientes são os
 * únicos consumidores de {@code RestClient} no repositório. Se aparecer um terceiro com outra
 * política de timeout, este bean vira qualificado em vez de global.
 */
@Configuration
public class ClientesExternosConfig {

  @Bean
  public RestClient.Builder restClientBuilder(
      @Value("${app.integracoes.timeout:PT2S}") Duration timeout) {
    SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
    fabrica.setConnectTimeout(timeout);
    fabrica.setReadTimeout(timeout);
    return RestClient.builder().requestFactory(fabrica);
  }

  /**
   * O relógio do disjuntor, injetável para que o teste avance o tempo em vez de dormir.
   *
   * <p>É o primeiro {@code Clock} do repositório e por isso pode ser global sem qualificador. A
   * transição "aberto → meia-abertura" é decidida comparando instantes, então ler {@code
   * System.currentTimeMillis()} direto tornaria o teste de recuperação um {@code sleep} de 30 s —
   * ou, pior, um teste que baixa a espera para milissegundos e passa a medir o escalonador do
   * sistema operacional em vez da regra.
   */
  @Bean
  public Clock relogio() {
    return Clock.systemUTC();
  }
}
