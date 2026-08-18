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
public abstract class ContainerConfig {

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

  /** Credenciais do papel de aplicação, criadas pela V2. Espelham application-dev.yml. */
  static final String APP_USUARIO = "omnitribo_app";

  static final String APP_SENHA = "omnitribo_app_dev";

  /**
   * DOIS usuários de banco, e a separação é o ponto — não detalhe de configuração de teste.
   *
   * <p>O Flyway conecta como o dono do contêiner: é ele que cria o schema E o papel {@code
   * omnitribo_app} (na V2). A APLICAÇÃO conecta como {@code omnitribo_app}, que tem apenas {@code
   * SELECT, INSERT} nas tabelas append-only.
   *
   * <p>Sem isso, o {@code REVOKE UPDATE, DELETE} das migrations era decorativo: a aplicação
   * conectava como o DONO das tabelas, para quem GRANT e REVOKE simplesmente não se aplicam. O
   * papel existia correto no catálogo e desligado em runtime — e nada acusava, porque um teste que
   * roda como dono nunca esbarra no privilégio que deveria estar protegendo o ledger.
   *
   * <p>A ordem funciona porque o Boot constrói um DataSource dedicado para o Flyway, migra, e só
   * então o {@code EntityManagerFactory} — que depende do {@code FlywayMigrationInitializer} — pede
   * a primeira conexão do pool principal, quando {@code omnitribo_app} já existe. Se algum dia não
   * valer, o sintoma é determinístico e barulhento: {@code FATAL: role "omnitribo_app" does not
   * exist} no boot de toda a suíte.
   */
  @DynamicPropertySource
  static void jdbcProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);

    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> APP_USUARIO);
    registry.add("spring.datasource.password", () -> APP_SENHA);
  }
}
