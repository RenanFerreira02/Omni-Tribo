package com.omnitribo.logistica.treino;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.omnitribo.logistica.dominio.CaracteristicaRisco;
import com.omnitribo.logistica.dominio.CodificadorEntrega;
import com.omnitribo.logistica.dominio.ParametrosRisco;
import com.omnitribo.logistica.dominio.PrevisorDeRisco;
import com.omnitribo.logistica.dominio.ResultadoRisco;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A prova de que o modelo publicado é REPRODUTÍVEL — e o teste central desta fase.
 *
 * <p>Re-treina do zero a partir da semente fixa e confere que os coeficientes do {@code
 * application.yml} são exatamente os que o treino produz. É o análogo de {@code
 * CalculadoraDeRecompensaTest.douradoV1}: existe para FALHAR quando alguém mexer no gerador, no
 * codificador ou nos hiperparâmetros sem republicar. Se ele quebrou e a mudança foi intencional, o
 * conserto não é afrouxar a tolerância — é rodar {@code tools/dataset/gerar.sh}, colar o bloco novo
 * no YAML e subir {@code app.logistica.risco.versao}.
 *
 * <p>Custa ~1,5 s, cacheado em {@code @BeforeAll} para a classe inteira.
 */
class ModeloRiscoTreinoTest {

  /**
   * Meia unidade da última casa publicada no YAML (6 decimais).
   *
   * <p>NÃO é fator de fudge: os coeficientes são gravados arredondados a 6 casas, então 5e-7 é
   * exatamente o erro de arredondamento e nada além dele. Como treinador e inferidor usam {@code
   * StrictMath} — especificado bit a bit, ao contrário de {@code Math} —, a diferença efetivamente
   * observada é da ordem de 1e-16, e o teste vale em qualquer JVM e arquitetura.
   */
  private static final double TOLERANCIA = 5e-7;

  private static ArtefatosDoModelo artefatos;
  private static ParametrosRisco publicado;

  @BeforeAll
  static void treinarUmaVez() {
    artefatos = ArtefatosDoModelo.treinar();
    publicado = ParametrosDoYaml.carregar();
  }

  // ─────────────────────────────── Convergência ───────────────────────────────

  @Test
  void o_gradiente_convergiu() {
    // Sem parada antecipada, a convergência precisa ser VERIFICADA em vez de assumida: 2000 épocas
    // é um número escolhido, não uma garantia. Gradiente perto de zero é a evidência de que o laço
    // chegou ao mínimo da função convexa.
    assertThat(artefatos.modelo().normaGradiente()).isLessThan(1e-3);
    assertThat(artefatos.modelo().logLossFinal()).isFinite().isLessThan(0.60);
  }

  // ─────────────────────── Reprodutibilidade do que foi publicado ───────────────────────

  @Test
  void os_coeficientes_publicados_reproduzem_o_treino() {
    assertThat(publicado.intercepto())
        .as("intercepto")
        .isCloseTo(artefatos.modelo().intercepto(), within(TOLERANCIA));

    for (CaracteristicaRisco c : CaracteristicaRisco.values()) {
      assertThat(publicado.coeficiente(c))
          .as("coeficiente de %s", c)
          .isCloseTo(artefatos.modelo().peso(c), within(TOLERANCIA));
    }
  }

  @Test
  void a_padronizacao_publicada_reproduz_a_do_treino() {
    for (CaracteristicaRisco c : CaracteristicaRisco.values()) {
      if (!c.numerica()) {
        continue;
      }
      ParametrosRisco.Padronizacao pad = publicado.padronizacao().get(c);
      assertThat(pad.media())
          .as("média de %s", c)
          .isCloseTo(artefatos.padronizador().media(c), within(TOLERANCIA));
      assertThat(pad.desvio())
          .as("desvio de %s", c)
          .isCloseTo(artefatos.padronizador().desvio(c), within(TOLERANCIA));
    }
  }

  @Test
  void o_limiar_publicado_e_o_que_a_varredura_escolhe_na_validacao() {
    assertThat(publicado.limiarAlto()).isCloseTo(artefatos.limiarAlto(), within(1e-9));
    assertThat(publicado.limiarMedio()).isCloseTo(artefatos.limiarMedio(), within(1e-9));
  }

  /**
   * A garantia MAIS FORTE das quatro, e a que pega o defeito que nenhuma outra pegaria.
   *
   * <p>Coeficiente igual não basta: se o codificador do treino divergisse do de produção — ordem de
   * dummies trocada, padronização aplicada a um indicador, referência diferente —, os coeficientes
   * continuariam idênticos e as PREVISÕES seriam outras. Este teste compara a saída da inferência
   * de runtime com a do treinador em TODA a partição de teste.
   */
  @Test
  void a_inferencia_de_runtime_reproduz_a_do_treinador() {
    double[][] x = artefatos.padronizador().transformar(artefatos.divisao().teste());

    for (int i = 0; i < artefatos.divisao().teste().size(); i++) {
      double doTreinador = artefatos.modelo().probabilidade(x[i]);
      ResultadoRisco doRuntime =
          PrevisorDeRisco.avaliar(artefatos.divisao().teste().get(i).features(), publicado);

      assertThat(doRuntime.probabilidadeFalha().doubleValue())
          .as("amostra %d de teste", i)
          .isCloseTo(doTreinador, within(1e-4));
    }
  }

  @Test
  void a_tabela_de_cep_publicada_e_a_mesma_que_o_treino_usou() {
    // Se divergissem, o runtime resolveria uma taxa histórica diferente da que o modelo viu no
    // treino, e o coeficiente de TAXA_HISTORICA_CEP passaria a multiplicar outra coisa.
    for (Map.Entry<String, Double> faixa : GeradorDatasetEntregas.TAXA_POR_FAIXA_CEP.entrySet()) {
      assertThat(publicado.taxaDaFaixaDeCep(faixa.getKey() + "00000"))
          .as("taxa da faixa %s", faixa.getKey())
          .isCloseTo(faixa.getValue(), within(1e-9));
    }
    assertThat(publicado.taxaCepPadrao())
        .as("taxa padrão deve ser a média do treino, para CEP desconhecido contribuir zero")
        .isCloseTo(
            artefatos.padronizador().media(CaracteristicaRisco.TAXA_HISTORICA_CEP), within(1e-6));
  }

  // ────────────────────────────── Qualidade do modelo ──────────────────────────────

  @Test
  void o_recall_no_teste_respeita_o_piso() {
    MatrizConfusao teste = artefatos.noTeste();

    // Recall é a métrica que este produto otimiza: falso negativo custa mais que falso positivo.
    assertThat(teste.recall()).as("recall no teste").isGreaterThanOrEqualTo(0.70);
    assertThat(teste.precisao()).as("precisão no teste").isGreaterThanOrEqualTo(0.33);
  }

  /**
   * Acurácia com TETO, e o teto é a parte que importa.
   *
   * <p>Acurácia acima de ~0,90 neste dataset seria impossível: o rótulo foi SORTEADO de {@code
   * Bernoulli(p)}, o que cria um erro de Bayes irredutível, e ainda há uma variável omitida e 2% de
   * rótulos invertidos. Um resultado alto demais significaria vazamento — provavelmente a
   * padronização ajustada sobre o dataset inteiro em vez de só o treino. O teste precisa REPROVAR
   * nesse caso, não comemorar.
   */
  @Test
  void a_acuracia_fica_dentro_da_faixa_plausivel() {
    double acuracia = artefatos.noTeste().acuracia();
    assertThat(acuracia).isBetween(0.55, 0.90);

    // E não pode passar do teto teórico do próprio dataset — o que o modelo VERDADEIRO acertaria.
    double teto = AvaliadorModelo.acuraciaMaximaTeorica(artefatos.divisao().teste());
    assertThat(acuracia)
        .as("nenhum modelo pode superar o erro de Bayes do dataset (%.4f)", teto)
        .isLessThanOrEqualTo(teto);
  }

  /**
   * O modelo carrega informação REAL, e não só reproduz a taxa-base.
   *
   * <p>Precisão maior que a prevalência é a definição de lift: entre as entregas que o modelo
   * marcou, a proporção de falhas é maior que na população. Sem isto, recall alto seria trivial —
   * bastaria marcar tudo.
   */
  @Test
  void o_modelo_tem_lift_sobre_a_taxa_base() {
    double prevalencia = artefatos.taxaBase();
    assertThat(artefatos.noTeste().precisao())
        .as(
            "precisão precisa superar a prevalência (%.4f), senão o modelo não informa nada",
            prevalencia)
        .isGreaterThan(prevalencia);
  }

  // ──────────────────────── O modelo aprendeu o mecanismo injetado ────────────────────────

  /**
   * Compara os coeficientes RECUPERADOS com os INJETADOS no gerador.
   *
   * <p>É a resposta com tabela — não retórica — para "como você sabe que o modelo aprendeu o
   * mecanismo em vez de decorar o dado?". Só as características com efeito FORTE e bem identificado
   * entram: as fracas e as colineares não são recuperáveis neste tamanho de amostra, e isso está
   * medido e documentado em {@code docs/qualidade/modelo-previsao.md} em vez de escondido
   * afrouxando a tolerância até tudo passar.
   */
  @Test
  void os_coeficientes_recuperados_batem_com_os_injetados_nos_efeitos_fortes() {
    for (CaracteristicaRisco c :
        java.util.List.of(
            CaracteristicaRisco.TENTATIVAS_ANTERIORES,
            CaracteristicaRisco.TAXA_HISTORICA_CEP,
            CaracteristicaRisco.ENDERECO_RURAL,
            CaracteristicaRisco.JANELA_NOITE,
            CaracteristicaRisco.COMERCIAL_EM_FIM_DE_SEMANA,
            CaracteristicaRisco.CHUVA_MM)) {

      double injetado = GeradorDatasetEntregas.COEFICIENTE_VERDADEIRO.get(c);
      double recuperado = artefatos.coeficienteBruto(c);

      assertThat(recuperado / injetado)
          .as(
              "razão recuperado/injetado de %s (injetado %+.4f, recuperado %+.4f)",
              c, injetado, recuperado)
          .isBetween(0.70, 1.30);
    }
  }

  @Test
  void o_sinal_de_todo_efeito_forte_foi_aprendido_corretamente() {
    for (CaracteristicaRisco c :
        java.util.List.of(
            CaracteristicaRisco.TENTATIVAS_ANTERIORES,
            CaracteristicaRisco.TAXA_HISTORICA_CEP,
            CaracteristicaRisco.ENDERECO_RURAL,
            CaracteristicaRisco.ENDERECO_CONDOMINIO,
            CaracteristicaRisco.JANELA_NOITE,
            CaracteristicaRisco.COMERCIAL_EM_FIM_DE_SEMANA)) {
      assertThat(artefatos.modelo().peso(c)).as("sinal de %s", c).isPositive();
    }
  }

  /**
   * A INTERAÇÃO foi aprendida, e é o resultado mais interessante do conjunto.
   *
   * <p>Um comércio no sábado precisa ser mais arriscado que a soma isolada de "é comércio" e "é fim
   * de semana" — é exatamente o que um modelo puramente aditivo NÃO conseguiria representar sem o
   * termo de produto. Que este teste passe é a evidência de que o termo está fazendo trabalho.
   */
  @Test
  void a_interacao_comercial_em_fim_de_semana_supera_a_soma_dos_efeitos_isolados() {
    double comercial = artefatos.modelo().peso(CaracteristicaRisco.ENDERECO_COMERCIAL);
    double fimDeSemana = artefatos.modelo().peso(CaracteristicaRisco.FIM_DE_SEMANA);
    double interacao = artefatos.modelo().peso(CaracteristicaRisco.COMERCIAL_EM_FIM_DE_SEMANA);

    assertThat(interacao).isGreaterThan(comercial + fimDeSemana);
  }

  // ─────────────────────────── Identidade da explicação ───────────────────────────

  /**
   * O log-odds é EXATAMENTE o intercepto mais a soma das contribuições.
   *
   * <p>Sem essa identidade, {@code fatoresPrincipais} seria decoração: números plausíveis que não
   * explicam de fato o resultado. Com ela, a explicação é auditável — dá para recalcular o score a
   * partir dos fatores exibidos.
   */
  @Test
  void a_soma_das_contribuicoes_reconstroi_o_log_odds() {
    var amostra = artefatos.divisao().teste().get(0);
    ResultadoRisco r = PrevisorDeRisco.avaliar(amostra.features(), publicado);

    double[] z = CodificadorEntrega.codificarEPadronizar(amostra.features(), publicado);
    double soma = publicado.intercepto();
    for (CaracteristicaRisco c : CaracteristicaRisco.values()) {
      soma += publicado.coeficiente(c) * z[c.indice()];
    }

    assertThat(r.logOdds()).isCloseTo(soma, within(1e-12));
    assertThat(r.logOddsBase()).isEqualTo(publicado.intercepto());
  }
}
