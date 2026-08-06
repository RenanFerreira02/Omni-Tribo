package com.omnitribo;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base para testes de integração que usam MockMvc. Paralela à TesteIntegracaoBase (RANDOM_PORT +
 * TestRestTemplate) — usa WebEnvironment.MOCK para acesso direto ao servlet, que permite
 * inspecionar headers de resposta, status codes e corpos sem roundtrip HTTP real.
 *
 * <p>O banco vem do contêiner singleton em ContainerConfig: um único PostgreSQL+PostGIS para toda a
 * JVM. Isso evita o CannotCreateTransactionException causado por contêineres parados entre classes.
 *
 * <p>O bean MockMvc é fornecido por {@link MockMvcTestConfig} via @Import — uma @TestConfiguration
 * top-level (e não aninhada aqui) para não ser detectada como "default configuration class", que o
 * Spring Framework 7.1 deixaria de ignorar.
 */
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@Import(MockMvcTestConfig.class)
@ActiveProfiles("test")
public abstract class TesteIntegracaoMvcBase extends ContainerConfig {

  /**
   * Mapper das assertions, construído aqui e não injetado — mesmo padrão e mesma razão do {@code
   * MAPPER_TRILHA} do MissaoService.
   *
   * <p>Injetar exigiria um bean, e o único que existia era de {@code
   * com.fasterxml.jackson.databind.ObjectMapper} (Jackson 2), enquanto a aplicação serializa com
   * Jackson 3 (tools.jackson). A suíte afirmava sobre um JSON parseado por uma major diferente da
   * que o produz. Construindo aqui, o teste lê com o mesmo Jackson que a API escreve e não depende
   * de autoconfiguração alguma.
   *
   * <p>Thread-safe depois de construído — o teste de aceite concorrente usa 50 threads.
   */
  protected static final JsonMapper JSON = JsonMapper.builder().build();

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
}
