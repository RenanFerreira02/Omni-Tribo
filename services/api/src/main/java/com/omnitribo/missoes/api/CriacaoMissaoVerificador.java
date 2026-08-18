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

    // A recusa de `valorBrl > 0` saiu daqui e virou @DecimalMax("0.00") no próprio campo: é regra
    // de
    // UM campo só, e não precisava de validador de classe. Continua sendo 400 com o campo apontado,
    // e não um 500 vindo da ck_missao_economia (V15) no INSERT.

    // ─── Insumos da fórmula de recompensa (ADR 0009) ────────────────────────────────────────
    //
    // A recompensa deixou de ser escolhida pelo criador e passou a ser derivada. Estas três regras
    // garantem que a derivação tem de que se alimentar, e que ninguém escolhe o multiplicador
    // quando existe dado objetivo para medi-lo.

    boolean temPeso = req.pesoKg() != null;
    boolean temVolume = req.volumeL() != null;
    boolean carregaCoisa =
        req.categoria() == CategoriaMissao.ENTREGA || req.categoria() == CategoriaMissao.COLETA;

    // (1) ENTREGA e COLETA movem objeto físico: sem peso e volume não há como dimensionar o
    // esforço, e a alternativa seria o criador declarar a complexidade — que é justamente o
    // arbítrio que esta mudança fecha.
    if (carregaCoisa && !temPeso) {
      violacao(contexto, "pesoKg", "Peso é obrigatório em missões ENTREGA e COLETA");
      valido = false;
    }
    if (carregaCoisa && !temVolume) {
      violacao(contexto, "volumeL", "Volume é obrigatório em missões ENTREGA e COLETA");
      valido = false;
    }

    // (2) Sem peso e volume (mutirão, ajuda sem carga), declarar é a única opção honesta.
    if (!temPeso && !temVolume && req.complexidade() == null) {
      violacao(
          contexto,
          "complexidade",
          "Complexidade é obrigatória quando a missão não tem peso e volume");
      valido = false;
    }

    // (3) Com peso e volume, o servidor deriva — e a declaração é RECUSADA, não ignorada.
    // Ignorar em silêncio faria o app acreditar que declarou algo que não teve efeito, e o usuário
    // veria uma recompensa que não bate com a complexidade que ele escolheu na tela.
    if (temPeso && temVolume && req.complexidade() != null) {
      violacao(
          contexto,
          "complexidade",
          "Complexidade é derivada de peso e volume — não informe junto com eles");
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
