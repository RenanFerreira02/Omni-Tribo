package com.omnitribo.identidade.dominio;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Verifica se a senha não está na lista de senhas mais comuns. */
@Component
public class SenhaComumVerificador
    implements ConstraintValidator<SenhaComumVerificador.ValidSenhaNaoComum, String> {

  private final Set<String> senhasComuns = new HashSet<>();

  @PostConstruct
  public void carregarLista() {
    // Carregado uma vez no startup: O(1) por verificação via HashSet.
    // A lista cobre padrões comuns mesmo com 12+ caracteres (ex.: "Password123!", "Abcd1234!@#$").
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                new ClassPathResource("senhas-comuns.txt").getInputStream(),
                StandardCharsets.UTF_8))) {
      reader.lines().map(String::trim).filter(l -> !l.isEmpty()).forEach(senhasComuns::add);
    } catch (IOException e) {
      throw new IllegalStateException("Falha ao carregar lista de senhas comuns", e);
    }
  }

  public boolean ehComum(String senha) {
    return senhasComuns.contains(senha.toLowerCase());
  }

  @Override
  public void initialize(ValidSenhaNaoComum annotation) {}

  @Override
  public boolean isValid(String senha, ConstraintValidatorContext context) {
    if (senha == null) return true; // @NotBlank cuida de null/blank
    return !ehComum(senha);
  }

  /** Anotação de Bean Validation para uso em DTOs de request. */
  @Target(ElementType.FIELD)
  @Retention(RetentionPolicy.RUNTIME)
  @Constraint(validatedBy = SenhaComumVerificador.class)
  public @interface ValidSenhaNaoComum {
    String message() default "Senha muito comum. Escolha uma senha mais única.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
  }
}
