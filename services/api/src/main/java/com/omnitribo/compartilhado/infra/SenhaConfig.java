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
    // Parâmetros Argon2id — MÍNIMO do OWASP Password Storage Cheat Sheet para Argon2id:
    //   m = 19456 KiB (19 MB), t = 2, p = 1, salt 16 B, hash 32 B.
    //
    // Era 16384 KiB, logo ABAIXO do mínimo recomendado. Subir não invalida nada: o Argon2 carrega
    // os próprios parâmetros dentro do hash, então senha antiga continua conferindo com m=16384 e
    // só a próxima gravação usa o valor novo.
    //
    // O custo é maior do que parece e vale saber por quê: o login gasta um Argon2 SEMPRE, inclusive
    // quando o e-mail não existe (o hashDummy de AutenticacaoService, que fecha o oráculo de
    // tempo).
    // Então ~20% a mais de latência aqui vale para todo login e todo registro — e é exatamente por
    // isso que o bloqueio progressivo e o rate limit precisam estar íntegros: sem eles, este custo
    // trabalha contra o defensor.
    // Referência: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
    return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
  }
}
