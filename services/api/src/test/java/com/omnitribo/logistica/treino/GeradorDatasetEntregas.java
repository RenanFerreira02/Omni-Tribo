package com.omnitribo.logistica.treino;

import com.omnitribo.logistica.dominio.CaracteristicaRisco;
import com.omnitribo.logistica.dominio.FeaturesEntrega;
import com.omnitribo.logistica.dominio.PrevisorDeRisco;
import com.omnitribo.logistica.dominio.TipoEndereco;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gera o dataset sintético de entregas com correlações INJETADAS e documentadas.
 *
 * <p><b>Estes dados não são reais.</b> São 5.000 entregas fabricadas por este código, e as
 * correlações que o modelo deve descobrir estão declaradas em {@link #COEFICIENTE_VERDADEIRO} —
 * literalmente escritas aqui antes de o modelo existir. É isso que permite validar que o treino
 * APRENDEU em vez de decorar: {@code CoeficientesRecuperadosTest} compara os coeficientes estimados
 * com os injetados. Validação contra dados reais da operação é o próximo passo, e nada aqui
 * substitui isso.
 *
 * <p><b>Como o rótulo nasce, e por que isso importa.</b> O desfecho é SORTEADO de {@code
 * Bernoulli(sigmoide(logOddsVerdadeiro))}, nunca decidido por um limiar. A diferença é o que separa
 * um dataset defensável de um brinquedo: com sorteio existe um <b>erro de Bayes irredutível</b> —
 * nenhum modelo, nem o que gerou os dados, consegue acurácia perfeita. Um dataset em que o rótulo é
 * função determinística das features produziria acurácia perto de 100%, que é o sinal mais suspeito
 * possível numa avaliação.
 *
 * <p>Sobre isso somam-se mais duas fontes de erro, cada uma imitando um fenômeno real: uma
 * <b>variável omitida</b> ({@code motoristaExperiente}, que afeta o desfecho e não é oferecida ao
 * modelo) e <b>2% de rótulos invertidos</b>, simulando erro de registro no coletor do motorista.
 */
public final class GeradorDatasetEntregas {

  /** Semente do dataset publicado. Trocá-la muda todos os coeficientes e exige republicar. */
  public static final long SEMENTE = 20260814L;

  /** Tamanho do dataset publicado. */
  public static final int REGISTROS = 5000;

  /**
   * As correlações injetadas, em ESCALA BRUTA (unidade natural de cada característica).
   *
   * <p>É a lista que {@code docs/qualidade/modelo-previsao.md} publica e que o modelo tem de
   * redescobrir. Cada número tem uma história operacional, e é a história — não o número — que se
   * defende oralmente.
   */
  public static final Map<CaracteristicaRisco, Double> COEFICIENTE_VERDADEIRO =
      criarCoeficientesVerdadeiros();

  /**
   * Intercepto verdadeiro, calibrado para que a taxa-base de falha do dataset fique perto de 20%.
   *
   * <p>20% é a ordem de grandeza que a literatura de última milha reporta para tentativa única em
   * área urbana densa. Não é medição nossa — é escolha de projeto, e está declarada como tal.
   */
  public static final double INTERCEPTO_VERDADEIRO = -3.60;

  /** Efeito do motorista experiente. NÃO é oferecido ao modelo — é a variável omitida. */
  public static final double COEFICIENTE_MOTORISTA_EXPERIENTE = -0.55;

  /** Fração de rótulos invertidos, simulando erro de registro. */
  public static final double PROPORCAO_RUIDO_ROTULO = 0.02;

  /**
   * Faixas de CEP com a taxa histórica de falha DECLARADA.
   *
   * <p>Prefixos de 3 dígitos da Grande São Paulo. As taxas são fabricadas, não medidas, e é
   * exatamente por serem fabricadas que o modelo consegue redescobri-las. <b>Esta tabela precisa
   * ser idêntica à publicada em {@code app.logistica.risco.taxa-por-faixa-cep}</b> — se divergirem,
   * o runtime resolveria uma taxa diferente da que o treino viu, e o coeficiente deixaria de valer.
   * {@code ModeloRiscoTreinoTest} confere.
   */
  public static final Map<String, Double> TAXA_POR_FAIXA_CEP = criarFaixasCep();

  private static final List<String> PREFIXOS = List.copyOf(TAXA_POR_FAIXA_CEP.keySet());

  private GeradorDatasetEntregas() {}

  private static Map<CaracteristicaRisco, Double> criarCoeficientesVerdadeiros() {
    EnumMap<CaracteristicaRisco, Double> m = new EnumMap<>(CaracteristicaRisco.class);
    // Quem já falhou uma vez tende a falhar de novo: destinatário com rotina incompatível com a
    // janela da transportadora. É, de longe, o preditor mais forte — e é o que a operação confirma.
    m.put(CaracteristicaRisco.TENTATIVAS_ANTERIORES, 1.10);
    // Jantar: ninguém atende o interfone, e a portaria trocou de turno.
    m.put(CaracteristicaRisco.JANELA_NOITE, 0.85);
    // Fora de qualquer janela em que alguém esteja disponível para receber.
    m.put(CaracteristicaRisco.JANELA_MADRUGADA, 0.60);
    // Levemente pior que a manhã: almoço e início de tarde esvaziam a casa.
    m.put(CaracteristicaRisco.JANELA_TARDE, 0.25);
    // Acesso difícil, endereçamento impreciso, distância entre pontos.
    m.put(CaracteristicaRisco.ENDERECO_RURAL, 0.70);
    // Portaria com regra própria: recusa por horário, por ausência de autorização, por lotação.
    m.put(CaracteristicaRisco.ENDERECO_CONDOMINIO, 0.45);
    // Em dia útil, comércio recebe BEM — o efeito isolado é pequeno.
    m.put(CaracteristicaRisco.ENDERECO_COMERCIAL, 0.30);
    // Residencial em fim de semana falha MENOS: as pessoas estão em casa.
    m.put(CaracteristicaRisco.FIM_DE_SEMANA, -0.10);
    // A INTERAÇÃO: comércio fechado no sábado. Efeito líquido de um comercial no fim de semana é
    // 0,30 - 0,10 + 1,30 = +1,50. Regressão logística só aprende isto porque o termo é oferecido.
    m.put(CaracteristicaRisco.COMERCIAL_EM_FIM_DE_SEMANA, 1.30);
    // Faixa a 30% de histórico contra uma a 5% ⇒ +1,00 no log-odds.
    m.put(CaracteristicaRisco.TAXA_HISTORICA_CEP, 4.00);
    // 15 mm de chuva ⇒ +0,68. Chuva atrasa a rota e desestimula descer para receber.
    m.put(CaracteristicaRisco.CHUVA_MM, 0.045);
    // 30 kg ⇒ +0,75: volume pesado exige alguém apto a receber, não só presente.
    m.put(CaracteristicaRisco.PESO_KG, 0.025);
    // 200 L ⇒ +0,80: não cabe no armário da portaria.
    m.put(CaracteristicaRisco.VOLUME_L, 0.004);
    // FRACO DE PROPÓSITO. O conjunto precisa de uma característica que quase não explica nada: sem
    // ela, "todas as minhas features são relevantes" é um resultado que não se sustenta na banca.
    m.put(CaracteristicaRisco.TEMPERATURA_C, 0.020);
    return java.util.Collections.unmodifiableMap(m);
  }

  private static Map<String, Double> criarFaixasCep() {
    LinkedHashMap<String, Double> m = new LinkedHashMap<>();
    m.put("010", 0.06);
    m.put("013", 0.09);
    m.put("020", 0.12);
    m.put("031", 0.28);
    m.put("035", 0.22);
    m.put("040", 0.08);
    m.put("043", 0.15);
    m.put("050", 0.11);
    m.put("054", 0.05);
    m.put("055", 0.19);
    m.put("080", 0.31);
    m.put("087", 0.25);
    return java.util.Collections.unmodifiableMap(m);
  }

  /** Gera o dataset publicado. */
  public static List<AmostraEntrega> gerar() {
    return gerar(SEMENTE, REGISTROS);
  }

  /**
   * Gera {@code quantidade} amostras a partir de {@code semente}.
   *
   * <p>A ordem dos sorteios dentro do laço faz parte do contrato: inserir um sorteio no meio
   * desloca todos os seguintes e muda o dataset inteiro. {@code DatasetSinteticoTest} pina isso com
   * um digest SHA-256.
   */
  public static List<AmostraEntrega> gerar(long semente, int quantidade) {
    RuidoDeterministico ruido = new RuidoDeterministico(semente);
    List<AmostraEntrega> amostras = new ArrayList<>(quantidade);

    for (int i = 0; i < quantidade; i++) {
      int hora = sortearHora(ruido);
      DayOfWeek dia = sortearDia(ruido);
      TipoEndereco tipo = sortearTipoEndereco(ruido);
      String cep = sortearCep(ruido, tipo);
      double taxaCep = TAXA_POR_FAIXA_CEP.get(cep.substring(0, 3));
      double pesoKg = sortearPeso(ruido);
      double volumeL = sortearVolume(ruido, pesoKg);
      double chuvaMm = sortearChuva(ruido, hora);
      double temperaturaC = sortearTemperatura(ruido, hora);
      int tentativas = sortearTentativas(ruido, taxaCep);
      boolean motoristaExperiente = ruido.bernoulli(0.60);

      FeaturesEntrega features =
          new FeaturesEntrega(
              hora, dia, tipo, taxaCep, pesoKg, volumeL, chuvaMm, temperaturaC, tentativas);

      double logOdds = logOddsVerdadeiro(features, motoristaExperiente);
      double p = PrevisorDeRisco.sigmoide(logOdds);

      boolean falhou = ruido.bernoulli(p);
      boolean invertido = ruido.bernoulli(PROPORCAO_RUIDO_ROTULO);
      if (invertido) {
        falhou = !falhou;
      }

      amostras.add(new AmostraEntrega(features, falhou, p, motoristaExperiente, invertido));
    }
    return List.copyOf(amostras);
  }

  /**
   * O log-odds que REALMENTE gerou o rótulo, incluindo a variável omitida.
   *
   * <p>Usa o mesmo {@code CodificadorEntrega} de produção, sobre o vetor BRUTO: é isso que faz os
   * coeficientes injetados estarem na mesma escala em que o treino pode recuperá-los.
   */
  private static double logOddsVerdadeiro(FeaturesEntrega f, boolean motoristaExperiente) {
    double[] bruto = com.omnitribo.logistica.dominio.CodificadorEntrega.codificar(f);
    double logOdds = INTERCEPTO_VERDADEIRO;
    for (CaracteristicaRisco c : CaracteristicaRisco.values()) {
      logOdds += COEFICIENTE_VERDADEIRO.get(c) * bruto[c.indice()];
    }
    if (motoristaExperiente) {
      logOdds += COEFICIENTE_MOTORISTA_EXPERIENTE;
    }
    return logOdds;
  }

  // ─────────────────────────── Distribuições das características ───────────────────────────

  /** Concentra em horário comercial, com cauda na madrugada. Pesos por hora, de 0h a 23h. */
  private static int sortearHora(RuidoDeterministico ruido) {
    double[] pesos = new double[24];
    for (int h = 0; h < 24; h++) {
      if (h >= 6 && h < 12) {
        pesos[h] = 3.0;
      } else if (h >= 12 && h < 18) {
        pesos[h] = 3.0;
      } else if (h >= 18 && h < 22) {
        pesos[h] = 1.6;
      } else {
        pesos[h] = 0.25;
      }
    }
    return ruido.categoria(pesos);
  }

  /** 5/7 dia útil, 2/7 fim de semana — a proporção natural da semana. */
  private static DayOfWeek sortearDia(RuidoDeterministico ruido) {
    return DayOfWeek.of(ruido.inteiro(7) + 1);
  }

  private static TipoEndereco sortearTipoEndereco(RuidoDeterministico ruido) {
    int i = ruido.categoria(55.0, 20.0, 18.0, 7.0);
    return switch (i) {
      case 0 -> TipoEndereco.RESIDENCIAL;
      case 1 -> TipoEndereco.COMERCIAL;
      case 2 -> TipoEndereco.CONDOMINIO;
      default -> TipoEndereco.RURAL;
    };
  }

  /**
   * CEP correlacionado com o tipo de endereço.
   *
   * <p>Endereço rural cai desproporcionalmente em faixas de taxa alta. Essa correlação ENTRE
   * características não é enfeite: é o que dá substância à pergunta sobre multicolinearidade e o
   * que justifica a regularização L2 do treinador.
   */
  private static String sortearCep(RuidoDeterministico ruido, TipoEndereco tipo) {
    List<String> ordenadosPorTaxa =
        PREFIXOS.stream()
            .sorted(java.util.Comparator.comparingDouble(TAXA_POR_FAIXA_CEP::get))
            .toList();
    int n = ordenadosPorTaxa.size();
    int indice;
    if (tipo == TipoEndereco.RURAL) {
      // Metade superior da tabela de taxas.
      indice = n / 2 + ruido.inteiro(n - n / 2);
    } else {
      indice = ruido.inteiro(n);
    }
    return ordenadosPorTaxa.get(indice) + "00000";
  }

  /** Log-normal: muitas encomendas leves, poucas muito pesadas. */
  private static double sortearPeso(RuidoDeterministico ruido) {
    double bruto = StrictMath.exp(1.0 + 0.8 * ruido.normal());
    return arredondar(limitar(bruto, 0.1, 30.0), 2);
  }

  /**
   * Correlacionado com o peso, mas com variação PRÓPRIA suficiente para ser identificável.
   *
   * <p>A densidade varia muito (um colchão e um halter de mesma massa ocupam volumes
   * incomparáveis), e é essa variação que o termo aditivo independente representa. Sem ela, volume
   * seria quase uma função linear do peso e a regressão não teria como separar os dois efeitos —
   * atribuiria tudo a um deles e devolveria coeficiente perto de zero, ou de sinal trocado, para o
   * outro. A correlação continua alta de propósito: é o que dá substância à discussão de
   * multicolinearidade e o que justifica a regularização L2 do treinador.
   */
  private static double sortearVolume(RuidoDeterministico ruido, double pesoKg) {
    double bruto = pesoKg * ruido.uniforme(2.0, 15.0) + StrictMath.abs(ruido.normal()) * 22.0;
    return arredondar(limitar(bruto, 1.0, 250.0), 2);
  }

  /** 70% sem chuva; probabilidade 50% maior na janela noturna. */
  private static double sortearChuva(RuidoDeterministico ruido, int hora) {
    boolean noite = hora >= 18 && hora < 22;
    double probabilidadeDeChuva = noite ? 0.45 : 0.30;
    if (!ruido.bernoulli(probabilidadeDeChuva)) {
      return 0.0;
    }
    double exponencial = -6.0 * StrictMath.log(StrictMath.max(ruido.uniforme(), 1e-12));
    return arredondar(limitar(exponencial, 0.1, 25.0), 1);
  }

  /** Ciclo diário, com pico às 15h. */
  private static double sortearTemperatura(RuidoDeterministico ruido, int hora) {
    double ciclo = 24.0 - 6.0 * StrictMath.cos(2.0 * StrictMath.PI * (hora - 15.0) / 24.0);
    return arredondar(limitar(ciclo + 3.0 * ruido.normal(), 12.0, 36.0), 1);
  }

  /** Maioria é primeira tentativa; faixa de CEP ruim eleva a chance de já ter havido tentativa. */
  private static int sortearTentativas(RuidoDeterministico ruido, double taxaCep) {
    double reforco = taxaCep > 0.18 ? 2.2 : 1.0;
    return ruido.categoria(78.0, 15.0 * reforco, 5.0 * reforco, 2.0 * reforco);
  }

  private static double limitar(double valor, double minimo, double maximo) {
    return StrictMath.min(StrictMath.max(valor, minimo), maximo);
  }

  private static double arredondar(double valor, int casas) {
    double fator = StrictMath.pow(10, casas);
    return StrictMath.round(valor * fator) / fator;
  }

  // ─────────────────────────────────── Digest canônico ───────────────────────────────────

  /**
   * SHA-256 sobre a representação canônica do dataset inteiro.
   *
   * <p>Pina o gerador MAIS FORTE que qualquer estatística agregada: taxa-base e correlações podem
   * continuar batendo depois de uma mudança que reordenou os sorteios, mas o digest não. Um bit
   * diferente em qualquer linha muda o hexadecimal.
   */
  public static String digestSha256(List<AmostraEntrega> amostras) {
    MessageDigest sha;
    try {
      sha = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 é obrigatório em toda JVM", e);
    }
    StringBuilder sb = new StringBuilder(64);
    for (AmostraEntrega a : amostras) {
      FeaturesEntrega f = a.features();
      sb.setLength(0);
      sb.append(f.horaDoDia())
          .append('|')
          .append(f.diaSemana().getValue())
          .append('|')
          .append(f.tipoEndereco().ordinal())
          .append('|')
          .append(Double.doubleToLongBits(f.taxaHistoricaCep()))
          .append('|')
          .append(Double.doubleToLongBits(f.pesoKg()))
          .append('|')
          .append(Double.doubleToLongBits(f.volumeL()))
          .append('|')
          .append(Double.doubleToLongBits(f.chuvaMm()))
          .append('|')
          .append(Double.doubleToLongBits(f.temperaturaC()))
          .append('|')
          .append(f.tentativasAnteriores())
          .append('|')
          .append(a.rotulo())
          .append('|')
          .append(a.motoristaExperiente() ? 1 : 0)
          .append('\n');
      sha.update(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
    return HexFormat.of().formatHex(sha.digest());
  }
}
