package com.omnitribo.arquitetura;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
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

  @Test
  void modulos_nao_acessam_dominio_nem_infra_de_outros_modulos() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.omnitribo");

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
