package com.omnitribo.logistica.dominio;

import java.util.Map;

/**
 * Traduz uma entrega para o vetor numérico que o modelo consome. Função pura.
 *
 * <p><b>Esta classe é o contrato compartilhado entre o treino e a produção.</b> O treinador, que
 * mora em {@code src/test}, usa exatamente estes métodos — nunca uma cópia. Se o encoder do treino
 * divergisse do de produção, o modelo passaria a somar o coeficiente de uma característica sobre o
 * valor de outra e <b>nenhum teste acusaria</b>: os números continuariam plausíveis, só que
 * errados. {@code ModeloRiscoTreinoTest} fecha essa porta comparando a inferência de runtime com a
 * do treinador em toda a partição de teste.
 *
 * <p>As janelas de hora e a categoria de endereço entram como indicadores com REFERÊNCIA
 * DESCARTADA: manhã (06h–11h) e {@code RESIDENCIAL} não têm posição no vetor. Ver {@link
 * CaracteristicaRisco} para por que descartar a referência é obrigatório e não economia.
 */
public final class CodificadorEntrega {

  private static final int INICIO_MANHA = 6;
  private static final int INICIO_TARDE = 12;
  private static final int INICIO_NOITE = 18;
  private static final int INICIO_MADRUGADA = 22;

  private CodificadorEntrega() {}

  /**
   * Vetor BRUTO: indicadores em 0/1, numéricas na unidade natural (kg, litros, mm, °C).
   *
   * <p>Separado de {@link #padronizar} porque o treinador precisa do bruto para CALCULAR μ e σ — e
   * só pode calculá-los depois de saber quais linhas caíram na partição de treino.
   */
  public static double[] codificar(FeaturesEntrega f) {
    double[] x = new double[CaracteristicaRisco.TOTAL];

    boolean comercial = f.tipoEndereco() == TipoEndereco.COMERCIAL;
    boolean fimDeSemana = f.fimDeSemana();

    x[CaracteristicaRisco.JANELA_MADRUGADA.indice()] = indicador(ehMadrugada(f.horaDoDia()));
    x[CaracteristicaRisco.JANELA_TARDE.indice()] = indicador(ehTarde(f.horaDoDia()));
    x[CaracteristicaRisco.JANELA_NOITE.indice()] = indicador(ehNoite(f.horaDoDia()));

    x[CaracteristicaRisco.ENDERECO_COMERCIAL.indice()] = indicador(comercial);
    x[CaracteristicaRisco.ENDERECO_CONDOMINIO.indice()] =
        indicador(f.tipoEndereco() == TipoEndereco.CONDOMINIO);
    x[CaracteristicaRisco.ENDERECO_RURAL.indice()] =
        indicador(f.tipoEndereco() == TipoEndereco.RURAL);

    x[CaracteristicaRisco.FIM_DE_SEMANA.indice()] = indicador(fimDeSemana);
    // Produto das duas dummies. Sem este termo, o modelo linear não tem como aprender que o efeito
    // de "comercial" DEPENDE de ser fim de semana — ele só somaria os dois efeitos isolados.
    x[CaracteristicaRisco.COMERCIAL_EM_FIM_DE_SEMANA.indice()] =
        indicador(comercial && fimDeSemana);

    x[CaracteristicaRisco.TAXA_HISTORICA_CEP.indice()] = f.taxaHistoricaCep();
    x[CaracteristicaRisco.PESO_KG.indice()] = f.pesoKg();
    x[CaracteristicaRisco.VOLUME_L.indice()] = f.volumeL();
    x[CaracteristicaRisco.CHUVA_MM.indice()] = f.chuvaMm();
    x[CaracteristicaRisco.TEMPERATURA_C.indice()] = f.temperaturaC();
    x[CaracteristicaRisco.TENTATIVAS_ANTERIORES.indice()] = f.tentativasAnteriores();

    return x;
  }

  /**
   * Aplica z-score nas numéricas; indicadores passam intactos.
   *
   * <p>Padronizar indicador produziria "log-odds por desvio-padrão de um indicador", que não
   * significa nada, e faria a AUSÊNCIA da característica contribuir com valor negativo diferente de
   * zero — quebrando a propriedade de que a categoria de referência contribui exatamente 0 e some
   * do ranking de fatores.
   */
  public static double[] padronizar(
      double[] bruto, Map<CaracteristicaRisco, ParametrosRisco.Padronizacao> padronizacao) {
    double[] z = new double[CaracteristicaRisco.TOTAL];
    // Laço pela ORDEM DO ENUM, nunca pela iteração de um mapa: a ordem de iteração de mapa imutável
    // varia entre execuções da JVM e decidiria a soma em ponto flutuante do log-odds.
    for (CaracteristicaRisco c : CaracteristicaRisco.values()) {
      int i = c.indice();
      if (!c.numerica()) {
        z[i] = bruto[i];
        continue;
      }
      ParametrosRisco.Padronizacao pad = padronizacao.get(c);
      if (pad == null) {
        throw new IllegalStateException("Sem padronização publicada para " + c);
      }
      z[i] = (bruto[i] - pad.media()) / pad.desvio();
    }
    return z;
  }

  /**
   * Sobrecarga que toma o mapa de dentro dos parâmetros publicados.
   *
   * <p>A assinatura acima recebe só o MAPA, e não o {@link ParametrosRisco} inteiro, por uma razão
   * concreta: durante o treino os coeficientes ainda não existem, então não há como construir um
   * {@code ParametrosRisco} válido — mas a padronização já precisa ser aplicada. Sem esta
   * separação, o treinador teria de reimplementar o z-score, e a divergência entre os dois
   * codificadores é exatamente o defeito que esta classe existe para impedir.
   */
  public static double[] padronizar(double[] bruto, ParametrosRisco p) {
    return padronizar(bruto, p.padronizacao());
  }

  /** Codifica e padroniza numa passada. É o caminho usado pela inferência de runtime. */
  public static double[] codificarEPadronizar(FeaturesEntrega f, ParametrosRisco p) {
    return padronizar(codificar(f), p.padronizacao());
  }

  private static double indicador(boolean ligado) {
    return ligado ? 1.0 : 0.0;
  }

  private static boolean ehMadrugada(int hora) {
    return hora >= INICIO_MADRUGADA || hora < INICIO_MANHA;
  }

  private static boolean ehTarde(int hora) {
    return hora >= INICIO_TARDE && hora < INICIO_NOITE;
  }

  private static boolean ehNoite(int hora) {
    return hora >= INICIO_NOITE && hora < INICIO_MADRUGADA;
  }
}
