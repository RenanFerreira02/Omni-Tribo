package com.omnitribo.logistica.treino;

import com.omnitribo.logistica.dominio.CaracteristicaRisco;
import com.omnitribo.logistica.dominio.FeaturesEntrega;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Executa o pipeline inteiro — gerar, dividir, padronizar, treinar, escolher limiar — e devolve os
 * artefatos publicáveis.
 *
 * <p>Existe para que o pipeline tenha UM caminho só, usado tanto pelo teste que prova
 * reprodutibilidade quanto pelo exportador que gera o CSV e o documento de métricas. Dois caminhos
 * seriam duas chances de divergir.
 */
final class ArtefatosDoModelo {

  private final List<AmostraEntrega> dataset;
  private final DivisaoDataset divisao;
  private final Padronizador padronizador;
  private final TreinadorRegressaoLogistica.ModeloTreinado modelo;
  private final double limiarAlto;

  private ArtefatosDoModelo(
      List<AmostraEntrega> dataset,
      DivisaoDataset divisao,
      Padronizador padronizador,
      TreinadorRegressaoLogistica.ModeloTreinado modelo,
      double limiarAlto) {
    this.dataset = dataset;
    this.divisao = divisao;
    this.padronizador = padronizador;
    this.modelo = modelo;
    this.limiarAlto = limiarAlto;
  }

  /**
   * Roda o pipeline completo com a semente publicada. ~1,5 s — vale cachear em {@code @BeforeAll}.
   */
  static ArtefatosDoModelo treinar() {
    List<AmostraEntrega> dataset = GeradorDatasetEntregas.gerar();
    DivisaoDataset divisao = DivisaoDataset.estratificar(dataset);
    Padronizador padronizador = Padronizador.ajustar(divisao.treino());
    TreinadorRegressaoLogistica.ModeloTreinado modelo =
        TreinadorRegressaoLogistica.treinar(
            padronizador.transformar(divisao.treino()), Padronizador.rotulos(divisao.treino()));
    double limiar = SeletorDeLimiar.escolher(divisao.validacao(), modelo, padronizador);
    return new ArtefatosDoModelo(dataset, divisao, padronizador, modelo, limiar);
  }

  List<AmostraEntrega> dataset() {
    return dataset;
  }

  DivisaoDataset divisao() {
    return divisao;
  }

  Padronizador padronizador() {
    return padronizador;
  }

  TreinadorRegressaoLogistica.ModeloTreinado modelo() {
    return modelo;
  }

  double limiarAlto() {
    return limiarAlto;
  }

  /** Metade do limiar de decisão, arredondada. Ver {@code FaixaRisco}. */
  double limiarMedio() {
    return arredondar(limiarAlto / 2.0, 2);
  }

  MatrizConfusao noTeste() {
    return AvaliadorModelo.avaliar(divisao.teste(), modelo, padronizador, limiarAlto);
  }

  MatrizConfusao noTreino() {
    return AvaliadorModelo.avaliar(divisao.treino(), modelo, padronizador, limiarAlto);
  }

  MatrizConfusao naValidacao() {
    return AvaliadorModelo.avaliar(divisao.validacao(), modelo, padronizador, limiarAlto);
  }

  /** Taxa-base de falha do dataset inteiro. */
  double taxaBase() {
    long falhas = dataset.stream().filter(AmostraEntrega::falhou).count();
    return (double) falhas / dataset.size();
  }

  /**
   * Coeficiente na escala BRUTA, recuperado do padronizado.
   *
   * <p>{@code β_bruto = β_padronizado / σ}. É nesta escala que se compara com o coeficiente
   * injetado no gerador — a prova de que o modelo aprendeu o mecanismo em vez de decorar o dado.
   */
  double coeficienteBruto(CaracteristicaRisco c) {
    double padronizado = modelo.peso(c);
    return c.numerica() ? padronizado / padronizador.desvio(c) : padronizado;
  }

  /**
   * O bloco YAML pronto para colar em {@code application.yml}.
   *
   * <p>{@code BigDecimal.toPlainString} e não {@code Double.toString}: o segundo pode emitir
   * notação científica ({@code 1.0E-4}), que o YAML lê como string e faria o binder falhar no boot.
   */
  String blocoYaml() {
    StringBuilder sb = new StringBuilder(2048);
    sb.append("      intercepto: ").append(seis(modelo.intercepto())).append('\n');
    sb.append("      coeficientes:\n");
    for (CaracteristicaRisco c : CaracteristicaRisco.values()) {
      sb.append(
          String.format(Locale.ROOT, "        %-27s %s%n", c.name() + ":", seis(modelo.peso(c))));
    }
    sb.append("      padronizacao:\n");
    for (CaracteristicaRisco c : CaracteristicaRisco.values()) {
      if (!c.numerica()) {
        continue;
      }
      sb.append(
          String.format(
              Locale.ROOT,
              "        %-23s { media: %s, desvio: %s }%n",
              c.name() + ":",
              seis(padronizador.media(c)),
              seis(padronizador.desvio(c))));
    }
    sb.append("      limiar-alto: ").append(duas(limiarAlto)).append('\n');
    sb.append("      limiar-medio: ").append(duas(limiarMedio())).append('\n');
    return sb.toString();
  }

  /** CSV do dataset, com cabeçalho. {@code Locale.ROOT} para não sair com vírgula decimal. */
  String csv() {
    StringBuilder sb = new StringBuilder(512 * 1024);
    sb.append("hora_do_dia,dia_semana,tipo_endereco,taxa_historica_cep,peso_kg,volume_l,")
        .append("chuva_mm,temperatura_c,tentativas_anteriores,motorista_experiente,")
        .append("probabilidade_verdadeira,rotulo_invertido,falhou\n");
    for (AmostraEntrega a : dataset) {
      FeaturesEntrega f = a.features();
      sb.append(
          String.format(
              Locale.ROOT,
              "%d,%s,%s,%.4f,%.2f,%.2f,%.1f,%.1f,%d,%d,%.6f,%d,%d%n",
              f.horaDoDia(),
              f.diaSemana().name(),
              f.tipoEndereco().name(),
              f.taxaHistoricaCep(),
              f.pesoKg(),
              f.volumeL(),
              f.chuvaMm(),
              f.temperaturaC(),
              f.tentativasAnteriores(),
              a.motoristaExperiente() ? 1 : 0,
              a.probabilidadeVerdadeira(),
              a.rotuloInvertido() ? 1 : 0,
              a.rotulo()));
    }
    return sb.toString();
  }

  /** Tabela de comparação entre coeficiente injetado e recuperado, para o documento. */
  String tabelaCoeficientes() {
    StringBuilder sb = new StringBuilder(2048);
    sb.append("| Característica | β injetado (bruto) | β recuperado (bruto) | ")
        .append("β padronizado (log-odds por desvio) |\n|---|---:|---:|---:|\n");
    Map<CaracteristicaRisco, Double> verdadeiros = GeradorDatasetEntregas.COEFICIENTE_VERDADEIRO;
    for (CaracteristicaRisco c : CaracteristicaRisco.values()) {
      sb.append(
          String.format(
              Locale.ROOT,
              "| `%s` | %+.4f | %+.4f | %+.4f |%n",
              c.name(),
              verdadeiros.get(c),
              coeficienteBruto(c),
              modelo.peso(c)));
    }
    return sb.toString();
  }

  /**
   * Tabela da varredura de limiar na validação, amostrada de 5 em 5 pontos para caber na página.
   */
  String tabelaLimiares() {
    StringBuilder sb = new StringBuilder(2048);
    sb.append("| Limiar | Acurácia | Precisão | Recall | F2 |\n|---:|---:|---:|---:|---:|\n");
    for (SeletorDeLimiar.Candidato c :
        SeletorDeLimiar.varrer(divisao.validacao(), modelo, padronizador)) {
      long passo = StrictMath.round(c.limiar() * 100);
      if (passo % 5 != 0) {
        continue;
      }
      sb.append(
          String.format(
              Locale.ROOT,
              "| %.2f | %.4f | %.4f | %.4f | %.4f |%s%n",
              c.limiar(),
              c.matriz().acuracia(),
              c.matriz().precisao(),
              c.matriz().recall(),
              c.matriz().f2(),
              StrictMath.abs(c.limiar() - limiarAlto) < 1e-9 ? "  **← escolhido**" : ""));
    }
    return sb.toString();
  }

  static String seis(double valor) {
    return BigDecimal.valueOf(valor).setScale(6, RoundingMode.HALF_UP).toPlainString();
  }

  static String duas(double valor) {
    return BigDecimal.valueOf(valor).setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static double arredondar(double valor, int casas) {
    return BigDecimal.valueOf(valor).setScale(casas, RoundingMode.HALF_UP).doubleValue();
  }
}
