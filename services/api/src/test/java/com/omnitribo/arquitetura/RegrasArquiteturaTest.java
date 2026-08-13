package com.omnitribo.arquitetura;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.omnitribo.compartilhado.api.RecursoAuditavel;
import com.omnitribo.compartilhado.dominio.Auditavel;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

class RegrasArquiteturaTest {

  // compartilhado é shared por design — aplica a regra só aos módulos de negócio
  private static final String[] MODULOS = {
    "identidade",
    "missoes",
    "geolocalizacao",
    "carteira",
    "logistica",
    "notificacoes",
    "integracoes"
  };

  private static JavaClasses classes() {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.omnitribo");
  }

  /**
   * A metade do {@code @Auditavel} que o compilador NÃO cobra.
   *
   * <p>Escrita auditável tem duas metades: a anotação no método e o {@code RecursoAuditavel} no
   * tipo devolvido. Faltando a segunda, tudo compila, nenhum teste fica vermelho, e o {@code
   * AuditoriaAspecto} grava {@code entidade_id} <b>NULL</b> — a trilha diz "alguém fez X" sem dizer
   * em QUÊ, que é justamente o que a torna inútil para reconstruir um incidente.
   *
   * <p>Não é hipótese: {@code ResultadoRegistroCheckin} estava assim, e todo check-in do sistema
   * gravou auditoria sem id de missão. Era o único dos 14 métodos anotados que não casava, e a
   * única forma de descobrir foi cruzar as duas listas à mão. Esta regra pega o 15º antes de ele
   * nascer.
   */
  @Test
  void todo_metodo_auditavel_devolve_um_recurso_auditavel() {
    methods()
        .that()
        .areAnnotatedWith(Auditavel.class)
        .should(
            new ArchCondition<JavaMethod>("devolver um tipo que implementa RecursoAuditavel") {
              @Override
              public void check(JavaMethod metodo, ConditionEvents eventos) {
                if (!metodo.getRawReturnType().isAssignableTo(RecursoAuditavel.class)) {
                  eventos.add(
                      SimpleConditionEvent.violated(
                          metodo,
                          metodo.getFullName()
                              + " é @Auditavel mas devolve "
                              + metodo.getRawReturnType().getSimpleName()
                              + ", que não implementa RecursoAuditavel —"
                              + " a auditoria gravaria entidade_id nulo."));
                }
              }
            })
        .check(classes());
  }

  /**
   * {@code compartilhado/infra} é adaptador PRIVADO: ninguém de fora fala com ele.
   *
   * <p>Fechava a única porta que a regra abaixo deixava aberta. {@code compartilhado} está fora do
   * array {@code MODULOS} — e precisa estar —, então nada impedia um domínio de qualquer módulo
   * importar um adaptador dele. Não era hipotético: {@code ConsultasGeoespaciais} era classe
   * concreta em {@code infra} e QUATRO domínios dependiam dela; {@code AutenticacaoService} chamava
   * {@code JwtService} e {@code BloqueioLoginService} direto. Domínio dependendo de infraestrutura
   * é exatamente a direção que o resto do projeto trata como proibida, e nenhum teste acusava.
   *
   * <p><b>A regra mira só {@code infra}, e a assimetria é a decisão.</b> Incluir {@code
   * compartilhado} inteiro no array acima protegeria também {@code compartilhado/dominio} — que tem
   * <b>46 imports legítimos</b> de outros módulos ({@code RecursoNaoEncontradoException}, {@code
   * Coordenadas}, {@code ChaveIdempotencia}, {@code Auditavel}). É kernel compartilhado por
   * desenho; o teste ficaria vermelho em quase todo arquivo do projeto e a única saída seria
   * desligá-lo. Então: {@code dominio} é kernel (livre), {@code api} é porta (livre), {@code infra}
   * é adaptador (fechado).
   */
  @Test
  void nenhum_modulo_alcanca_a_infraestrutura_do_compartilhado() {
    noClasses()
        .that()
        .resideOutsideOfPackage("com.omnitribo.compartilhado..")
        .should()
        .accessClassesThat()
        .resideInAPackage("com.omnitribo.compartilhado.infra..")
        .as("compartilhado/infra é adaptador privado: fale por uma porta em compartilhado/api")
        .check(classes());
  }

  @Test
  void modulos_nao_acessam_dominio_nem_infra_de_outros_modulos() {
    JavaClasses classes = classes();

    for (String modulo : MODULOS) {
      noClasses()
          .that()
          .resideOutsideOfPackage("com.omnitribo." + modulo + "..")
          .should()
          .accessClassesThat()
          .resideInAnyPackage(
              "com.omnitribo." + modulo + ".dominio..", "com.omnitribo." + modulo + ".infra..")
          .as("Módulo '" + modulo + "': dominio e infra são pacotes internos")
          .check(classes);
    }
  }
}
