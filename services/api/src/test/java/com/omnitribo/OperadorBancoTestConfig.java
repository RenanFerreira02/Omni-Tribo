package com.omnitribo;

import javax.sql.DataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dá aos testes um {@link JdbcTemplate} de OPERADOR, separado do datasource da aplicação.
 *
 * <p><b>Por que passou a ser necessário.</b> A aplicação agora conecta como {@code omnitribo_app},
 * que tem só {@code SELECT, INSERT} nas quatro tabelas append-only — é isso que faz o {@code REVOKE
 * UPDATE, DELETE} das migrations valer em RUNTIME, e não apenas no catálogo do PostgreSQL. Mas os
 * testes fazem {@code DELETE FROM lancamento|auditoria|checkin|missao_evento} em 33 lugares para
 * limpar estado entre casos, e todos passariam a falhar com {@code permission denied}.
 *
 * <p>A saída não é afrouxar o papel da aplicação — isso jogaria fora justamente a proteção que a
 * mudança existe para criar. É reconhecer que <b>o teste não é a aplicação</b>: quando ele limpa
 * uma tabela append-only, está no papel de operador do banco, que em produção seria uma pessoa com
 * acesso administrativo, não o serviço.
 *
 * <p>{@code @Primary} para que os 33 sites existentes continuem funcionando sem edição: quem injeta
 * {@code JdbcTemplate} recebe este. O datasource da aplicação continua sendo o do Spring Boot, com
 * {@code omnitribo_app} — e é sobre ele que {@code MigracaoTest} prova que o ledger é imutável.
 */
@TestConfiguration
public class OperadorBancoTestConfig {

  /**
   * Conecta como o dono do banco (o superusuário do contêiner), que é também quem o Flyway usa.
   *
   * <p>Construído à mão, e não derivado do datasource da aplicação: o ponto inteiro é ter uma
   * credencial DIFERENTE. Pool minúsculo porque este datasource serve arranjo e limpeza, uma
   * consulta por vez — e cada conexão a mais aqui conta contra o {@code max_connections} do
   * servidor, que já é o gargalo da suíte (ver {@code ContainerConfig}).
   */
  @Bean
  @Primary
  public JdbcTemplate jdbcTemplateOperador() {
    DataSource dono =
        DataSourceBuilder.create()
            .url(ContainerConfig.POSTGRES.getJdbcUrl())
            .username(ContainerConfig.POSTGRES.getUsername())
            .password(ContainerConfig.POSTGRES.getPassword())
            .build();
    return new JdbcTemplate(dono);
  }
}
