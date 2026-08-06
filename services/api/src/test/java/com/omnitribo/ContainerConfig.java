package com.omnitribo;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton de contêiner Testcontainers compartilhado entre todas as classes de teste.
 *
 * <p>Por que não @Testcontainers + @Container: quando essas anotações estão numa classe abstrata, o
 * JUnit inicia e para um contêiner por classe de teste concreta. Se duas classes compartilham o
 * mesmo Spring context (mesma chave de cache), a segunda encontra o HikariPool do contexto
 * apontando para o contêiner que já foi parado pela primeira — CannotCreateTransactionException.
 *
 * <p>Aqui o contêiner é iniciado uma única vez no bloco static, vive durante toda a JVM e é
 * destruído pelo mecanismo Ryuk do Testcontainers ao final. @DynamicPropertySource propaga a URL
 * para todos os contextos Spring das subclasses.
 */
abstract class ContainerConfig {

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void jdbcProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }
}
