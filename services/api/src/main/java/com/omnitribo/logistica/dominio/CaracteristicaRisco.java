package com.omnitribo.logistica.dominio;

/**
 * Registro das características que alimentam o modelo de risco, e o CONTRATO DA ORDEM DO VETOR.
 *
 * <p><b>O índice de cada característica é o {@code ordinal()}, e a ordem de declaração abaixo é o
 * contrato.</b> Reordenar, inserir no meio ou remover uma constante invalida TODOS os coeficientes
 * publicados em {@code app.logistica.risco.coeficientes} — o modelo passaria a somar o coeficiente
 * de uma característica sobre o valor de outra, silenciosamente, produzindo probabilidades erradas
 * sem nenhum erro de compilação. Quem mexer aqui precisa re-treinar e republicar; {@code
 * ModeloRiscoTreinoTest} falha de propósito para forçar essa decisão, no mesmo espírito de {@code
 * CalculadoraDeRecompensaTest.douradoV1}.
 *
 * <p><b>Este enum vive em {@code src/main} de propósito, mesmo sendo usado pelo treinador que mora
 * em {@code src/test}.</b> O classpath de teste enxerga o principal, o contrário não — então uma
 * única definição serve aos dois lados e é impossível o codificador do treino divergir do
 * codificador de produção. Duplicá-lo seria o modo de falha real desta feature: nenhum teste
 * acusaria a divergência.
 *
 * <p><b>Por que categórica vira indicador com referência DESCARTADA.</b> {@code MANHA} (06–11h) e
 * {@code RESIDENCIAL} não têm constante aqui: são as categorias de referência. Com todas as dummies
 * presentes a soma delas seria sempre 1, o intercepto viraria combinação linear delas, o sistema
 * ficaria indeterminado e a regularização escolheria arbitrariamente uma solução entre infinitas —
 * o coeficiente perderia interpretação. Descartando a referência, {@code β(ENDERECO_RURAL) = +0,70}
 * lê-se exatamente como "endereço rural soma 0,70 ao log-odds EM RELAÇÃO a um residencial
 * equivalente", que é a frase que se defende oralmente.
 *
 * <p><b>Indicador não é padronizado; numérica é.</b> Padronizar um indicador produziria "log-odds
 * por desvio-padrão de um indicador", que não significa nada em português, e faria a AUSÊNCIA da
 * característica contribuir com um valor negativo diferente de zero — destruindo a propriedade
 * limpa de que a categoria de referência contribui exatamente 0 e some do ranking de fatores.
 */
public enum CaracteristicaRisco {

  /** Janela 22h–05h. Referência: MANHA (06h–11h). */
  JANELA_MADRUGADA("Janela madrugada (22h–05h)", false),
  /** Janela 12h–17h. */
  JANELA_TARDE("Janela tarde (12h–17h)", false),
  /** Janela 18h–21h. */
  JANELA_NOITE("Janela noturna (18h–21h)", false),

  /** Referência: RESIDENCIAL. */
  ENDERECO_COMERCIAL("Endereço comercial", false),
  ENDERECO_CONDOMINIO("Endereço em condomínio", false),
  ENDERECO_RURAL("Endereço rural", false),

  FIM_DE_SEMANA("Fim de semana", false),

  /**
   * Termo de INTERAÇÃO: produto de {@code ENDERECO_COMERCIAL} por {@code FIM_DE_SEMANA}.
   *
   * <p>Existe porque regressão logística é aditiva no log-odds e <b>não descobre interação
   * sozinha</b>. O efeito "comércio fechado no fim de semana" só é aprendível se o termo for
   * oferecido explicitamente. Isso é uma limitação medida do modelo linear — uma árvore encontraria
   * a interação por conta própria — e está registrada no ADR 0022 em vez de escondida.
   */
  COMERCIAL_EM_FIM_DE_SEMANA("Comércio em fim de semana", false),

  TAXA_HISTORICA_CEP("Taxa histórica de falha da faixa de CEP", true),
  PESO_KG("Peso do volume", true),
  VOLUME_L("Volume da encomenda", true),
  CHUVA_MM("Chuva na janela de entrega", true),
  TEMPERATURA_C("Temperatura", true),
  TENTATIVAS_ANTERIORES("Tentativas anteriores de entrega", true);

  /** Quantidade de características. É a dimensão do vetor de entrada do modelo. */
  public static final int TOTAL = values().length;

  private final String rotulo;
  private final boolean numerica;

  CaracteristicaRisco(String rotulo, boolean numerica) {
    this.rotulo = rotulo;
    this.numerica = numerica;
  }

  /**
   * Índice desta característica no vetor de features.
   *
   * <p>Método nomeado em vez de {@code ordinal()} solto no código de chamada: deixa explícito, na
   * leitura, que a posição no vetor é intencional e não um acidente da ordem de declaração.
   */
  public int indice() {
    return ordinal();
  }

  /** Se recebe padronização z-score. Indicadores (0/1) não recebem — ver javadoc da classe. */
  public boolean numerica() {
    return numerica;
  }

  /** Texto legível, exibido em {@code fatoresPrincipais}. */
  public String rotulo() {
    return rotulo;
  }
}
