package com.omnitribo.logistica.treino;

import com.omnitribo.logistica.dominio.CaracteristicaRisco;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Relatório de inspeção do treino. Só roda sob demanda — ver {@code tools/dataset/gerar.sh}. */
@EnabledIfSystemProperty(named = "relatorioModelo", matches = "true")
class RelatorioTreinoTest {

  @Test
  void imprimir() {
    ArtefatosDoModelo a = ArtefatosDoModelo.treinar();

    System.out.println("=== DATASET ===");
    System.out.printf(
        Locale.ROOT,
        "registros=%d taxaBase=%.4f digest=%s%n",
        a.dataset().size(),
        a.taxaBase(),
        GeradorDatasetEntregas.digestSha256(a.dataset()));
    System.out.printf(
        Locale.ROOT,
        "treino=%d validacao=%d teste=%d%n",
        a.divisao().treino().size(),
        a.divisao().validacao().size(),
        a.divisao().teste().size());
    System.out.printf(
        Locale.ROOT,
        "acuraciaMaximaTeorica(teste)=%.4f%n",
        AvaliadorModelo.acuraciaMaximaTeorica(a.divisao().teste()));

    System.out.println("=== CONVERGENCIA ===");
    System.out.printf(
        Locale.ROOT,
        "logLoss=%.6f normaGradiente=%.10f%n",
        a.modelo().logLossFinal(),
        a.modelo().normaGradiente());

    System.out.println("=== LIMIAR ===");
    System.out.printf(
        Locale.ROOT, "limiarAlto=%.2f limiarMedio=%.2f%n", a.limiarAlto(), a.limiarMedio());

    System.out.println("=== METRICAS ===");
    System.out.println(a.noTreino().linhaMarkdown("treino"));
    System.out.println(a.naValidacao().linhaMarkdown("validacao"));
    System.out.println(a.noTeste().linhaMarkdown("teste"));

    System.out.println("=== CALIBRACAO (teste) ===");
    System.out.print(a.tabelaCalibracao());

    System.out.println("=== BRIER (teste) ===");
    System.out.printf(
        Locale.ROOT,
        "brierModelo=%.4f brierChute=%.4f ganho=%.4f erroCalibracao=%.4f%n",
        a.brierNoTeste(),
        a.brierDoChuteNoTeste(),
        a.ganhoSobreChute(),
        AvaliadorCalibracao.erroDeCalibracao(a.calibracaoNoTeste()));

    System.out.println("=== VARREDURA DE LIMIAR (validacao) ===");
    System.out.println(a.tabelaLimiares());

    System.out.println("=== COEFICIENTES ===");
    System.out.println(a.tabelaCoeficientes());

    System.out.println("=== YAML ===");
    System.out.println(a.blocoYaml());

    // Marcador sem barra: gerar.sh recorta estas seções com `sed`, e uma barra no endereço
    // quebraria o comando silenciosamente — o arquivo sairia com lixo em vez de coeficientes.
    System.out.println("=== RAZAO ===");
    for (CaracteristicaRisco c : CaracteristicaRisco.values()) {
      double injetado = GeradorDatasetEntregas.COEFICIENTE_VERDADEIRO.get(c);
      System.out.printf(
          Locale.ROOT,
          "%-28s %+.4f / %+.4f = %.3f%n",
          c.name(),
          a.coeficienteBruto(c),
          injetado,
          a.coeficienteBruto(c) / injetado);
    }
  }
}
