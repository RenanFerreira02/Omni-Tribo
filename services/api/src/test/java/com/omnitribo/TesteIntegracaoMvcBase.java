package com.omnitribo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Base para testes de integração que usam MockMvc. Paralela à TesteIntegracaoBase (RANDOM_PORT +
 * TestRestTemplate) — usa WebEnvironment.MOCK para acesso direto ao servlet, que permite
 * inspecionar headers de resposta, status codes e corpos sem roundtrip HTTP real.
 *
 * <p>O banco vem do contêiner singleton em ContainerConfig: um único PostgreSQL+PostGIS para toda a
 * JVM. Isso evita o CannotCreateTransactionException causado por contêineres parados entre classes.
 *
 * <p>MockMvc é criado manualmente em vez de @AutoConfigureMockMvc (removido no Spring Boot 4.1). O
 * bean é exposto via MockMvcAutoConfig para que subclasses possam @Autowired MockMvc.
 */
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@Import(TesteIntegracaoMvcBase.MockMvcAutoConfig.class)
@ActiveProfiles("test")
public abstract class TesteIntegracaoMvcBase extends ContainerConfig {

  // Par RSA gerado programaticamente para testes: não sensível, não versionado em arquivo.
  // Permite criar JWTs válidos, expirados e com assinatura incorreta sem depender de arquivos PEM.
  protected static final KeyPair PAR_RSA_TESTE = gerarParRsa();

  private static KeyPair gerarParRsa() {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      return gen.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("RSA não disponível", e);
    }
  }

  protected static PrivateKey chavePrivadaTeste() {
    return PAR_RSA_TESTE.getPrivate();
  }

  protected static PublicKey chavePublicaTeste() {
    return PAR_RSA_TESTE.getPublic();
  }

  /**
   * Cria o bean MockMvc com o filtro de segurança do Spring Security aplicado. Em Spring Boot
   * 4.1, @AutoConfigureMockMvc foi removido; este @TestConfiguration substitui a funcionalidade. A
   * anotação @Import na classe-base garante que todas as subclasses herdem o bean.
   */
  @TestConfiguration
  static class MockMvcAutoConfig {

    @Bean
    MockMvc mockMvc(WebApplicationContext wac) {
      return MockMvcBuilders.webAppContextSetup(wac)
          .apply(SecurityMockMvcConfigurers.springSecurity())
          .build();
    }

    // JacksonAutoConfiguration pode não ser ativado no contexto WebEnvironment.MOCK em
    // Spring Boot 4.1. Este bean garante que ObjectMapper esteja disponível para testes.
    @Bean
    @ConditionalOnMissingBean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
