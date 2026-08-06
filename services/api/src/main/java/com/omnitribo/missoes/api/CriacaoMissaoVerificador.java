package com.omnitribo.missoes.api;

import com.omnitribo.missoes.dominio.CategoriaMissao;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;

/**
 * Regras de criação de missão que dependem de mais de um campo.
 *
 * <p>Toda violação é ancorada num campo com addPropertyNode: uma constraint em nível de classe
 * produz um ObjectError, não um FieldError, e o GlobalExceptionHandler só lê getFieldErrors(). Sem
 * a âncora, o erro sumiria da lista "errors" da resposta e o cliente receberia um 400 sem saber o
 * que corrigir.
 */
public class CriacaoMissaoVerificador
    implements ConstraintValidator<
        CriacaoMissaoVerificador.MissaoCriacaoConsistente, CriarMissaoRequest> {

  @Override
  public boolean isValid(CriarMissaoRequest req, ConstraintValidatorContext contexto) {
    if (req == null) {
      return true;
    }

    boolean valido = true;
    contexto.disableDefaultConstraintViolation();

    if (req.janelaInicio() != null
        && req.janelaFim() != null
        && !req.janelaFim().isAfter(req.janelaInicio())) {
      violacao(contexto, "janelaFim", "Fim da janela deve ser posterior ao início");
      valido = false;
    }

    if ((req.destinoLat() == null) != (req.destinoLon() == null)) {
      violacao(
          contexto, "destinoLat", "Latitude e longitude de destino devem ser informadas juntas");
      valido = false;
    }

    // Invariante econômica das três moedas: missões comunitárias recompensam em token e XP.
    // O banco tem a mesma regra em ck_missao_economia (V3); aqui ela vira um 400 com o campo
    // apontado, em vez de um 500 vindo de violação de constraint no INSERT.
    if (ehComunitaria(req.categoria())
        && req.valorBrl() != null
        && req.valorBrl().compareTo(BigDecimal.ZERO) > 0) {
      violacao(
          contexto,
          "valorBrl",
          "Missões TRIBO e COLETA não podem ter valor em BRL — recompensam em tokens e XP");
      valido = false;
    }

    return valido;
  }

  private static boolean ehComunitaria(CategoriaMissao categoria) {
    return categoria == CategoriaMissao.TRIBO || categoria == CategoriaMissao.COLETA;
  }

  private static void violacao(ConstraintValidatorContext contexto, String campo, String mensagem) {
    contexto
        .buildConstraintViolationWithTemplate(mensagem)
        .addPropertyNode(campo)
        .addConstraintViolation();
  }

  /** Anotação de classe aplicada em {@link CriarMissaoRequest}. */
  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  @Constraint(validatedBy = CriacaoMissaoVerificador.class)
  public @interface MissaoCriacaoConsistente {
    String message() default "Dados da missão inconsistentes";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
  }
}
