package com.omnitribo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Cria o bean MockMvc com o filtro do Spring Security aplicado, para os testes de integração
 * baseados em WebEnvironment.MOCK (ver {@link TesteIntegracaoMvcBase}). Em Spring Boot 4.1
 * o @AutoConfigureMockMvc foi removido; esta @TestConfiguration substitui a funcionalidade.
 *
 * <p>É uma classe top-level de propósito, e não aninhada em TesteIntegracaoMvcBase:
 * uma @Configuration estática aninhada na classe-base de teste é detectada pelo Spring como
 * "default configuration class" e, a partir do Spring Framework 7.1, deixaria de ser ignorada —
 * alterando o contexto de teste silenciosamente (o build atual emite esse warning). Como classe
 * top-level ativada apenas por @Import, o wiring é explícito e imune a essa mudança de versão.
 */
@TestConfiguration
public class MockMvcTestConfig {

  @Bean
  MockMvc mockMvc(WebApplicationContext wac) {
    return MockMvcBuilders.webAppContextSetup(wac)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
  }

  // JacksonAutoConfiguration pode não ser ativado no contexto WebEnvironment.MOCK em Spring Boot
  // 4.1. Este bean garante que ObjectMapper esteja disponível para testes.
  @Bean
  @ConditionalOnMissingBean
  ObjectMapper objectMapper() {
    return new ObjectMapper();
  }
}
