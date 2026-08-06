package com.omnitribo.missoes.api;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Regras multi-campo do PATCH.
 *
 * <p>Não valida economia: o PATCH não altera recompensa nem categoria, então a invariante das três
 * moedas não pode ser quebrada por aqui.
 */
public class EdicaoMissaoVerificador
    implements ConstraintValidator<
        EdicaoMissaoVerificador.MissaoEdicaoConsistente, AtualizarMissaoRequest> {

  @Override
  public boolean isValid(AtualizarMissaoRequest req, ConstraintValidatorContext contexto) {
    if (req == null) {
      return true;
    }

    boolean valido = true;
    contexto.disableDefaultConstraintViolation();

    // Janela: só valida quando ambos vêm no corpo. Enviar um só dos dois é ambíguo — o outro
    // extremo continuaria valendo o antigo e poderia inverter a janela sem que se percebesse.
    if ((req.janelaInicio() == null) != (req.janelaFim() == null)) {
      violacao(contexto, "janelaFim", "Início e fim da janela devem ser alterados juntos");
      valido = false;
    } else if (req.janelaInicio() != null && !req.janelaFim().isAfter(req.janelaInicio())) {
      violacao(contexto, "janelaFim", "Fim da janela deve ser posterior ao início");
      valido = false;
    }

    if ((req.origemLat() == null) != (req.origemLon() == null)) {
      violacao(contexto, "origemLat", "Latitude e longitude de origem devem ser informadas juntas");
      valido = false;
    }

    if ((req.destinoLat() == null) != (req.destinoLon() == null)) {
      violacao(
          contexto, "destinoLat", "Latitude e longitude de destino devem ser informadas juntas");
      valido = false;
    }

    return valido;
  }

  private static void violacao(ConstraintValidatorContext contexto, String campo, String mensagem) {
    contexto
        .buildConstraintViolationWithTemplate(mensagem)
        .addPropertyNode(campo)
        .addConstraintViolation();
  }

  /** Anotação de classe aplicada em {@link AtualizarMissaoRequest}. */
  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  @Constraint(validatedBy = EdicaoMissaoVerificador.class)
  public @interface MissaoEdicaoConsistente {
    String message() default "Dados de edição inconsistentes";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
  }
}
