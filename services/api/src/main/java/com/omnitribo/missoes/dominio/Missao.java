package com.omnitribo.missoes.dominio;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "missao")
public class Missao {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  // criadorId/executorId: FK para usuario — missoes→identidade é permitido
  @Column(name = "criador_id", nullable = false, updatable = false)
  private UUID criadorId;

  @Column(name = "executor_id")
  private UUID executorId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private CategoriaMissao categoria;

  @Column(nullable = false, length = 200)
  private String titulo;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String descricao;

  // length = 30 acompanha a V11: 'AGUARDANDO_CONFIRMACAO' tem 22 caracteres.
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private StatusMissao status;

  @Column(name = "xp_recompensa", nullable = false)
  private int xpRecompensa;

  // numeric(12,2) → BigDecimal; nunca double (erros de ponto flutuante em BRL)
  @Column(name = "valor_brl", nullable = false, precision = 12, scale = 2)
  private BigDecimal valorBrl;

  @Column(name = "tokens_recompensa", nullable = false)
  private long tokensRecompensa;

  // length = 7 casa com o VARCHAR(7) da V16 — ddl-auto é validate e reprova divergência.
  @Enumerated(EnumType.STRING)
  @Column(length = 7)
  private ComplexidadeMissao complexidade;

  /**
   * Versão da calibração que produziu a recompensa. Congelada e nunca recalculada: é o que
   * responde, meses depois, se um crédito estava certo quando foi feito. Nula antes da V16.
   */
  @Column(name = "versao_formula")
  private Integer versaoFormula;

  /** Reservado: nenhum código lê ou escreve hoje. */
  @Column(name = "multiplicador_risco", precision = 4, scale = 2)
  private BigDecimal multiplicadorRisco;

  /**
   * Faixa de risco congelada (nula fora de entrega falida). {@code String} porque o enum {@code
   * FaixaRisco} é de {@code logistica} e o ArchUnit proíbe importá-lo — sempre o {@code name()} de
   * um enum já validado.
   */
  @Column(name = "faixa_risco", length = 5)
  private String faixaRisco;

  @Column(nullable = false, columnDefinition = "geography(POINT,4326)")
  private Point origem;

  @Column(columnDefinition = "geography(POINT,4326)")
  private Point destino;

  // UUID puro, sem FK: fronteira missoes→logistica; ver V3__missoes.sql
  @Column(name = "ponto_custodia_id")
  private UUID pontoCustodiaId;

  @Column(nullable = false, length = 8)
  private String cep;

  @Column(nullable = false, length = 200)
  private String logradouro;

  @Column(nullable = false, length = 100)
  private String bairro;

  @Column(nullable = false, length = 100)
  private String cidade;

  @Column(nullable = false, length = 2)
  private String uf;

  @Column(name = "raio_checkin_m", nullable = false)
  private int raioCheckinM;

  // peso e volume informam recompensa e instruções de manuseio
  @Column(name = "peso_kg", precision = 6, scale = 2)
  private BigDecimal pesoKg;

  @Column(name = "volume_l", precision = 8, scale = 2)
  private BigDecimal volumeL;

  @Column(name = "janela_inicio", nullable = false)
  private Instant janelaInicio;

  @Column(name = "janela_fim", nullable = false)
  private Instant janelaFim;

  @Column(name = "criada_em", nullable = false, updatable = false)
  private Instant criadaEm;

  @Column(name = "aceita_em")
  private Instant aceitaEm;

  @Column(name = "concluida_em")
  private Instant concluidaEm;

  /**
   * Desde quando a missão está no status ATUAL, carimbado pela máquina de estados a cada transição.
   * É o marco das varreduras por prazo: {@code janelaFim} é prazo de oferta e {@code aceitaEm} zera
   * no DESISTIR.
   */
  @Column(name = "estado_desde", nullable = false)
  private Instant estadoDesde;

  // Quanto de fato existe para pagar (o que a missão PROMETE é tokensRecompensa). A conclusão
  // debita
  // daqui, sem emitir token novo.
  @Column(name = "pote_tokens", nullable = false)
  private long poteTokens;

  /**
   * Nível mínimo para ACEITAR. 1 = sem restrição, o valor de toda missão criada por usuário (quem
   * publica não escolhe quem executa). Só a entrega falida grava mais: encomenda de terceiro não
   * fica aberta a conta recém-criada. Coluna por missão para recalibrar não reescrever o passado.
   */
  @Column(name = "nivel_minimo", nullable = false)
  private int nivelMinimo = 1;

  /**
   * De onde sai o token da recompensa, congelado no construtor ({@link FontePote}). A única
   * transição é {@link #financiadaPeloPatrocinador()}, antes de publicar.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "fonte_pote", nullable = false, length = 12)
  private FontePote fontePote;

  @Version
  @Column(nullable = false)
  private int versao;

  protected Missao() {}

  /**
   * Construtor de criação: tudo de uma vez, sem setter por campo — setter reabriria mass
   * assignment.
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Point de JTS é imutável após construção; cópia defensiva sem benefício")
  public Missao(
      UUID id,
      UUID criadorId,
      CategoriaMissao categoria,
      String titulo,
      String descricao,
      StatusMissao status,
      CalculadoraDeRecompensa.Recompensa recompensa,
      BigDecimal valorBrl,
      Point origem,
      Point destino,
      UUID pontoCustodiaId,
      String cep,
      String logradouro,
      String bairro,
      String cidade,
      String uf,
      int raioCheckinM,
      BigDecimal pesoKg,
      BigDecimal volumeL,
      Instant janelaInicio,
      Instant janelaFim,
      Instant criadaEm) {
    this.id = id;
    this.criadorId = criadorId;
    this.categoria = categoria;
    this.titulo = titulo;
    this.descricao = descricao;
    this.status = status;
    // Recompensa como UM objeto: neste construtor longo, dois int seriam intercambiáveis para o
    // compilador e uma inversão passaria em silêncio.
    this.xpRecompensa = recompensa.xp();
    this.tokensRecompensa = recompensa.tokens();
    this.complexidade = recompensa.complexidade();
    this.versaoFormula = recompensa.versaoFormula();
    // Congelado com a versão da fórmula: crédito antigo continua explicável depois de re-treinar o
    // risco.
    this.multiplicadorRisco = recompensa.multiplicadorRisco();
    this.valorBrl = valorBrl;
    this.origem = origem;
    this.destino = destino;
    this.pontoCustodiaId = pontoCustodiaId;
    this.cep = cep;
    this.logradouro = logradouro;
    this.bairro = bairro;
    this.cidade = cidade;
    this.uf = uf;
    this.raioCheckinM = raioCheckinM;
    this.pesoKg = pesoKg;
    this.volumeL = volumeL;
    this.janelaInicio = janelaInicio;
    this.janelaFim = janelaFim;
    this.criadaEm = criadaEm;
    // A missão nasce RASCUNHO neste instante. A partir daqui quem mexe é a máquina de estados.
    this.estadoDesde = criadaEm;
    // Derivada e congelada aqui, ponto único de toda criação. Sem CHECK no banco: seeds inserem
    // depois.
    this.fontePote = derivarFontePote(recompensa);
  }

  /**
   * De onde sairá o token (ADR 0038): toda missão de pessoa paga do pote de OUTROS membros, ou é só
   * XP; nenhuma nova emite. A de retirada nasce COMUNIDADE e passa a PATROCINADOR na mesma
   * transação. {@code tokens == 0} só nasce de {@code Recompensa.semToken()} — a calculadora tem
   * piso de 1.
   */
  private static FontePote derivarFontePote(CalculadoraDeRecompensa.Recompensa recompensa) {
    return recompensa.tokens() == 0 ? FontePote.SEM_TOKEN : FontePote.COMUNIDADE;
  }

  /**
   * Recongela a recompensa depois de um PATCH nos insumos (ADR 0036). Quem protege o pote já
   * comprometido é {@code MissaoService.recongelarRecompensaEditada}, que recusa antes. A CUNHAGEM
   * legada mantém a fonte, ou passaria a exigir um pote que nunca teve.
   *
   * @throws IllegalStateException fora de RASCUNHO/ABERTA — erro de programação; o serviço já
   *     recusou.
   */
  public void recongelarRecompensa(CalculadoraDeRecompensa.Recompensa recompensa) {
    if (this.status != StatusMissao.RASCUNHO && this.status != StatusMissao.ABERTA) {
      throw new IllegalStateException(
          "Recompensa só pode ser recongelada em RASCUNHO ou ABERTA; esta missão está em "
              + this.status
              + ".");
    }
    this.xpRecompensa = recompensa.xp();
    this.tokensRecompensa = recompensa.tokens();
    this.complexidade = recompensa.complexidade();
    this.versaoFormula = recompensa.versaoFormula();
    this.multiplicadorRisco = recompensa.multiplicadorRisco();
    boolean legadoCunhado = this.fontePote == FontePote.CUNHAGEM && recompensa.tokens() > 0;
    this.fontePote = legadoCunhado ? FontePote.CUNHAGEM : derivarFontePote(recompensa);
  }

  /**
   * Edição dos campos descritivos e logísticos (PATCH). Recompensa, categoria, criador, executor e
   * status ficam de fora; status e executor só mudam pela máquina de estados.
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Point de JTS é imutável após construção; cópia defensiva sem benefício")
  public void editarRascunho(
      String titulo,
      String descricao,
      String cep,
      String logradouro,
      String bairro,
      String cidade,
      String uf,
      Point origem,
      Point destino,
      Instant janelaInicio,
      Instant janelaFim,
      int raioCheckinM,
      BigDecimal pesoKg,
      BigDecimal volumeL) {
    this.titulo = titulo;
    this.descricao = descricao;
    this.cep = cep;
    this.logradouro = logradouro;
    this.bairro = bairro;
    this.cidade = cidade;
    this.uf = uf;
    this.origem = origem;
    this.destino = destino;
    this.janelaInicio = janelaInicio;
    this.janelaFim = janelaFim;
    this.raioCheckinM = raioCheckinM;
    this.pesoKg = pesoKg;
    this.volumeL = volumeL;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCriadorId() {
    return criadorId;
  }

  public UUID getExecutorId() {
    return executorId;
  }

  public void setExecutorId(UUID executorId) {
    this.executorId = executorId;
  }

  public CategoriaMissao getCategoria() {
    return categoria;
  }

  public String getTitulo() {
    return titulo;
  }

  public String getDescricao() {
    return descricao;
  }

  public StatusMissao getStatus() {
    return status;
  }

  /**
   * Muda o status E carimba {@code estadoDesde}, sempre juntos: um carimbo velho faria a varredura
   * expirar uma missão que acabou de transicionar.
   */
  public void setStatus(StatusMissao status, Instant quando) {
    this.status = status;
    this.estadoDesde = quando;
  }

  public Instant getEstadoDesde() {
    return estadoDesde;
  }

  public int getXpRecompensa() {
    return xpRecompensa;
  }

  public BigDecimal getValorBrl() {
    return valorBrl;
  }

  public long getTokensRecompensa() {
    return tokensRecompensa;
  }

  public ComplexidadeMissao getComplexidade() {
    return complexidade;
  }

  public Integer getVersaoFormula() {
    return versaoFormula;
  }

  public BigDecimal getMultiplicadorRisco() {
    return multiplicadorRisco;
  }

  public String getFaixaRisco() {
    return faixaRisco;
  }

  /**
   * Registra a faixa de risco (rótulo de apresentação, que não é insumo da fórmula). Uma vez, na
   * conversão de entrega falida.
   */
  public void registrarFaixaRisco(String faixa) {
    this.faixaRisco = faixa;
  }

  public FontePote getFontePote() {
    return fontePote;
  }

  /**
   * Marca a missão como financiada pelo patrocinador, uma vez, na conversão de entrega falida.
   *
   * <p>Só aceita a missão recém-criada — {@code COMUNIDADE} com pote VAZIO: trocar a fonte de um
   * pote que vizinhos já financiaram faria o estorno procurar o motivo errado e prender o dinheiro.
   */
  public void financiadaPeloPatrocinador() {
    if (this.fontePote != FontePote.COMUNIDADE || this.poteTokens != 0) {
      throw new IllegalStateException(
          "Só missão COMUNIDADE com pote vazio pode passar a PATROCINADOR; esta é "
              + this.fontePote
              + " com pote "
              + this.poteTokens
              + ".");
    }
    this.fontePote = FontePote.PATROCINADOR;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Point de JTS é imutável após construção; cópia defensiva sem benefício")
  public Point getOrigem() {
    return origem;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Point de JTS é imutável após construção; cópia defensiva sem benefício")
  public Point getDestino() {
    return destino;
  }

  public UUID getPontoCustodiaId() {
    return pontoCustodiaId;
  }

  public String getCep() {
    return cep;
  }

  public String getLogradouro() {
    return logradouro;
  }

  public String getBairro() {
    return bairro;
  }

  public String getCidade() {
    return cidade;
  }

  public String getUf() {
    return uf;
  }

  public int getRaioCheckinM() {
    return raioCheckinM;
  }

  public BigDecimal getPesoKg() {
    return pesoKg;
  }

  public BigDecimal getVolumeL() {
    return volumeL;
  }

  public Instant getJanelaInicio() {
    return janelaInicio;
  }

  public Instant getJanelaFim() {
    return janelaFim;
  }

  public Instant getCriadaEm() {
    return criadaEm;
  }

  public Instant getAceitaEm() {
    return aceitaEm;
  }

  public void setAceitaEm(Instant aceitaEm) {
    this.aceitaEm = aceitaEm;
  }

  public Instant getConcluidaEm() {
    return concluidaEm;
  }

  public void setConcluidaEm(Instant concluidaEm) {
    this.concluidaEm = concluidaEm;
  }

  public long getPoteTokens() {
    return poteTokens;
  }

  /** Financiamento recebido de um membro da tribo. Chamado sob o lock da linha da missão. */
  public void creditarPote(long tokens) {
    if (tokens <= 0) {
      throw new IllegalArgumentException("Crédito no pote precisa ser positivo.");
    }
    this.poteTokens += tokens;
  }

  /**
   * Pagamento saindo do pote. A guarda é de erro de programação: quem chama já verificou sob lock e
   * respondeu 422. Um 500 alto é melhor que um pote negativo silencioso.
   */
  public void debitarPote(long tokens) {
    if (tokens <= 0) {
      throw new IllegalArgumentException("Débito do pote precisa ser positivo.");
    }
    if (tokens > this.poteTokens) {
      throw new IllegalStateException(
          "Débito de " + tokens + " excede o pote de " + this.poteTokens + ".");
    }
    this.poteTokens -= tokens;
  }

  /** Zera o pote ao estornar todos os financiadores em CANCELADA/EXPIRADA. */
  public void zerarPote() {
    this.poteTokens = 0;
  }

  public int getVersao() {
    return versao;
  }

  public int getNivelMinimo() {
    return nivelMinimo;
  }

  /**
   * Exige reputação para aceitar. Nenhum DTO alcança este método; só a conversão de entrega falida.
   */
  void exigirNivelMinimo(int nivel) {
    if (nivel < 1) {
      throw new IllegalArgumentException("Nível mínimo deve ser ao menos 1, veio " + nivel);
    }
    this.nivelMinimo = nivel;
  }
}
