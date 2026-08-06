package com.omnitribo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// UserDetailsServiceAutoConfiguration excluída de propósito: a API é stateless e autentica por JWT
// (ver SecurityConfig + JwtAuthFilter + AutenticacaoService, que casa a senha via PasswordEncoder).
// Sem a exclusão, o Spring Boot cria um InMemoryUserDetailsManager com senha aleatória impressa no
// log a cada boot — um usuário que nada consulta, que só polui o log e sugere um mecanismo de
// autenticação que não existe neste serviço.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan // descobre @ConfigurationProperties (JwtProperties) sem @Bean manual
public class ApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(ApiApplication.class, args);
  }
}
