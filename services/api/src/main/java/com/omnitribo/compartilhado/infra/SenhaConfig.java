package com.omnitribo.compartilhado.infra;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SenhaConfig {

  @Bean
  public PasswordEncoder passwordEncoder() {
    // DelegatingPasswordEncoder: reconhece o algoritmo pelo prefixo {id} no hash armazenado.
    // "argon2" é o default para NOVAS senhas; {bcrypt} cobre hashes legados (seeds anteriores
    // a esta fase) e permite migração gradual sem forçar reset de senha.
    Map<String, PasswordEncoder> encoders =
        Map.of("argon2", argon2Encoder(), "bcrypt", new BCryptPasswordEncoder(12));
    return new DelegatingPasswordEncoder("argon2", encoders);
  }

  private Argon2PasswordEncoder argon2Encoder() {
    // Parâmetros Argon2id — OWASP Password Storage Cheat Sheet, configuração C (conservadora):
    //   memória 16 MB (16384 KiB), 2 iterações, paralelismo 1, salt 16 B, hash 32 B.
    // Custo típico em dev: ~80-120 ms — inibe brute-force offline mesmo com GPU.
    // Para produção com hardware dedicado: aumentar memória para 64 MB e iterações para 3.
    // Referência: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
    return new Argon2PasswordEncoder(16, 32, 1, 16384, 2);
  }
}
