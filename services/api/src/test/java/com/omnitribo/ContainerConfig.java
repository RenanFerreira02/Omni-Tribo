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

  /**
   * {@code max_connections} elevado de 100 (padrão) para 500.
   *
   * <p>O limite não é por pool, é por SERVIDOR, e a suíte mantém VÁRIOS contextos Spring vivos ao
   * mesmo tempo — o cache de contexto do Spring não os descarta entre classes, e {@code
   * ConclusaoRollbackTest} tem contexto próprio por causa do {@code @MockitoBean}. Cada contexto
   * carrega o seu HikariPool inteiro, então o consumo é (nº de contextos × maximum-pool-size), não
   * o tamanho de um pool.
   *
   * <p>Sem isto, subir o pool para acomodar o teste de 100 threads derruba o contexto de outra
   * classe com {@code FATAL: sorry, too many clients already} — uma falha que se apresenta como
   * "Failed to load ApplicationContext" e não aponta em nada para a causa real.
   *
   * <p><b>A conta a refazer ao adicionar teste:</b> o consumo é (nº de contextos × 40). E ganha
   * contexto próprio toda classe que muda a configuração do Spring — hoje {@code @MockitoBean}
   * ({@code ConclusaoRollbackTest}), {@code @MockitoSpyBean} ({@code EnumeracaoUsuarioTest}) e
   * {@code @TestPropertySource} ({@code SaqueDesabilitadoTest}). Foi exatamente ao acrescentar as
   * duas últimas que 300 deixou de bastar. Se uma classe nova falhar ao carregar contexto sem
   * motivo aparente, suspeite deste número antes de procurar o erro no código dela.
   */
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"))
          .withCommand("postgres", "-c", "max_connections=500");

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
