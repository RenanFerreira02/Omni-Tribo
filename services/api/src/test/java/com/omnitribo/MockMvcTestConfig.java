package com.omnitribo;

import com.omnitribo.compartilhado.infra.CorrelationIdFilter;
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

  /**
   * O CorrelationIdFilter é registrado explicitamente porque o MockMvc não herda os filtros de
   * servlet que o Spring Boot auto-registra a partir de @Component — só monta a cadeia do Spring
   * Security. (O RateLimitFilter funciona nos testes justamente por estar dentro dessa cadeia, via
   * addFilterBefore no SecurityConfig.) Sem este registro o MDC fica vazio sob MockMvc, e tanto o
   * correlationId da auditoria quanto o traceId do ProblemDetail seriam nulos apenas em teste —
   * divergência silenciosa entre o que a suíte exercita e o que roda em produção.
   */
  @Bean
  MockMvc mockMvc(WebApplicationContext wac, CorrelationIdFilter correlationIdFilter) {
    return MockMvcBuilders.webAppContextSetup(wac)
        .addFilters(correlationIdFilter)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .build();
  }
}
