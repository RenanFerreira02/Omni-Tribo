package com.omnitribo.logistica.treino;

import com.omnitribo.logistica.dominio.CaracteristicaRisco;
import com.omnitribo.logistica.dominio.CodificadorEntrega;
import com.omnitribo.logistica.dominio.ParametrosRisco;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Média e desvio-padrão das características numéricas, ajustados <b>só na partição de treino</b>.
 *
 * <p><b>Ajustar sobre o dataset inteiro seria vazamento</b>, e é um erro que uma banca atenta
 * procura: a média e o desvio do conjunto de teste entrariam na transformação aplicada ao treino, o
 * modelo veria de esguelha informação que deveria ser cega para ele, e as métricas reportadas
 * sairiam otimistas. Por isso {@link #ajustar} recebe explicitamente a lista de TREINO.
 *
 * <p>A média também é o valor de imputação usado em produção: característica ausente entra com
 * z-score exatamente 0 — contribuição nula, ou seja, o modelo não tem opinião sobre ela. É o
 * comportamento honesto quando o dado não existe.
 */
final class Padronizador {

  private final Map<CaracteristicaRisco, ParametrosRisco.Padronizacao> parametros;

  private Padronizador(Map<CaracteristicaRisco, ParametrosRisco.Padronizacao> parametros) {
    this.parametros = parametros;
  }

  static Padronizador ajustar(List<AmostraEntrega> treino) {
    if (treino.isEmpty()) {
      throw new IllegalArgumentException("Não há como ajustar padronização sobre partição vazia");
    }
    EnumMap<CaracteristicaRisco, ParametrosRisco.Padronizacao> mapa =
        new EnumMap<>(CaracteristicaRisco.class);

    for (CaracteristicaRisco c : CaracteristicaRisco.values()) {
      if (!c.numerica()) {
        continue;
      }
      double soma = 0.0;
      for (AmostraEntrega a : treino) {
        soma += CodificadorEntrega.codificar(a.features())[c.indice()];
      }
      double media = soma / treino.size();

      double somaQuadrados = 0.0;
      for (AmostraEntrega a : treino) {
        double desvio = CodificadorEntrega.codificar(a.features())[c.indice()] - media;
        somaQuadrados += desvio * desvio;
      }
      // Desvio POPULACIONAL (divide por n), não amostral: a partição de treino é a população sobre
      // a qual o modelo foi ajustado, e usar n-1 aqui só introduziria uma diferença sem sentido
      // entre o que o treino aplicou e o que o runtime aplica.
      double desvioPadrao = StrictMath.sqrt(somaQuadrados / treino.size());
      if (!(desvioPadrao > 0.0)) {
        throw new IllegalStateException(
            "Característica "
                + c
                + " é constante na partição de treino — z-score dividiria por"
                + " zero. Revise o gerador antes de treinar.");
      }
      mapa.put(c, new ParametrosRisco.Padronizacao(media, desvioPadrao));
    }
    return new Padronizador(java.util.Collections.unmodifiableMap(mapa));
  }

  Map<CaracteristicaRisco, ParametrosRisco.Padronizacao> parametros() {
    return parametros;
  }

  double media(CaracteristicaRisco c) {
    return parametros.get(c).media();
  }

  double desvio(CaracteristicaRisco c) {
    return parametros.get(c).desvio();
  }

  /** Matriz padronizada, na MESMA ordem da lista recebida. */
  double[][] transformar(List<AmostraEntrega> amostras) {
    double[][] x = new double[amostras.size()][];
    for (int i = 0; i < amostras.size(); i++) {
      x[i] =
          CodificadorEntrega.padronizar(
              CodificadorEntrega.codificar(amostras.get(i).features()), parametros);
    }
    return x;
  }

  static int[] rotulos(List<AmostraEntrega> amostras) {
    int[] y = new int[amostras.size()];
    for (int i = 0; i < amostras.size(); i++) {
      y[i] = amostras.get(i).rotulo();
    }
    return y;
  }
}
