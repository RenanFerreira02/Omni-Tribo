package com.omnitribo.missoes.api;

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

    // Invariante econômica: NENHUMA categoria remunera em BRL. Quem cria a missão não paga —
    // a recompensa é XP e TOKEN, resgatável em benefícios de parceiros (ADR 0009).
    //
    // A regra deixou de depender da categoria: antes só TRIBO e COLETA eram barradas, e era
    // justamente por ENTREGA e AJUDA aceitarem valor_brl que a conclusão creditava dinheiro sem
    // débito em lugar nenhum. O banco tem a mesma regra em ck_missao_economia (V15); esta
    // checagem existe para o cliente receber 400 com o campo apontado, e não um 500 vindo de
    // violação de constraint no INSERT.
    if (req.valorBrl() != null && req.valorBrl().compareTo(BigDecimal.ZERO) > 0) {
      violacao(
          contexto,
          "valorBrl",
          "Missão não remunera em BRL nesta versão — a recompensa é em XP e tokens");
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
