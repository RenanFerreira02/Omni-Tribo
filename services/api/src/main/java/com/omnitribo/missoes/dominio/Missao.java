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
   * Versão da calibração que produziu xpRecompensa e tokensRecompensa.
   *
   * <p>Congelada na criação e NUNCA recalculada. É o que permite responder, meses depois, se um
   * crédito estava certo quando foi feito — sem ela, mudar um parâmetro reinterpreta
   * retroativamente toda missão já aceita. Nula em missões anteriores à V16, que não vieram de
   * fórmula alguma.
   */
  @Column(name = "versao_formula")
  private Integer versaoFormula;

  /**
   * Reservado para a F11. Nenhum código lê ou escreve hoje; entrou junto para que o congelamento da
   * recompensa já nascesse completo.
   */
  @Column(name = "multiplicador_risco", precision = 4, scale = 2)
  private BigDecimal multiplicadorRisco;

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
   * Desde quando a missão está no status ATUAL. Atualizado por {@code MissaoStateMachine} a cada
   * transição, sem exceção.
   *
   * <p>Existe porque nenhum marco anterior responde "há quanto tempo esta missão está parada AQUI".
   * {@code janelaFim} é o prazo de OFERTA — vira passado assim que alguém aceita e não diz nada
   * sobre execução; {@code aceitaEm} é zerado no DESISTIR; {@code criadaEm} é imóvel. Sem este
   * campo, a varredura de {@code EM_ANDAMENTO} e {@code AGUARDANDO_CONFIRMACAO} teria de inferir o
   * instante lendo a trilha {@code missao_evento}, o que faria o job depender de um JOIN por linha.
   */
  @Column(name = "estado_desde", nullable = false)
  private Instant estadoDesde;

  // Tokens já financiados por membros da tribo, em custódia até a conclusão pagar o
  // executor. NÃO é recompensa: `tokensRecompensa` é quanto a missão promete pagar,
  // `poteTokens` é quanto de fato existe para pagar. Concluir uma missão TRIBO/COLETA
  // debita daqui em vez de cunhar token novo — é o que conserva a oferta da moeda.
  @Column(name = "pote_tokens", nullable = false)
  private long poteTokens;

  /**
   * Nível mínimo (derivado do XP por {@code RegraNivel}) exigido para ACEITAR esta missão.
   *
   * <p>1 significa "sem restrição", e é o valor de toda missão criada por usuário — o DTO de
   * criação não tem este campo, e não deve ter: quem publica não escolhe quem pode executar. Só a
   * conversão de entrega falida grava valor maior, e o motivo é a Regra de Elegibilidade por
   * Reputação do challenge: custódia de pacote de terceiro não fica visível para toda a base.
   *
   * <p>Coluna por missão, e não constante no serviço, para que a regra fique auditável junto com a
   * missão que a aplicou — recalibrar o mínimo depois não reescreve o passado.
   */
  @Column(name = "nivel_minimo", nullable = false)
  private int nivelMinimo = 1;

  @Version
  @Column(nullable = false)
  private int versao;

  protected Missao() {}

  /**
   * Construtor de criação. Recebe tudo de uma vez em vez de expor um setter por campo: um setter
   * por campo reabriria o caminho de mass assignment que este módulo existe para fechar.
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
    // Recompensa chega como UM objeto, e não como três parâmetros soltos, de propósito: neste
    // construtor de 20+ posições, xpRecompensa (int) e raioCheckinM (int) são intercambiáveis para
    // o
    // compilador, e uma inversão passaria silenciosa. O record torna a troca impossível.
    this.xpRecompensa = recompensa.xp();
    this.tokensRecompensa = recompensa.tokens();
    this.complexidade = recompensa.complexidade();
    this.versaoFormula = recompensa.versaoFormula();
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
  }

  /**
   * Edição dos campos descritivos e logísticos (PATCH).
   *
   * <p>Recompensa (BRL, tokens, XP), categoria, criador, executor e status ficam deliberadamente de
   * fora: alterar a recompensa com a missão já ABERTA mudaria o contrato sob os pés de quem está
   * prestes a aceitar, e status/executor só mudam pela máquina de estados.
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
   * Muda o status E carimba desde quando ele vale — as duas coisas juntas, sempre.
   *
   * <p>Um {@code setStatus} que não tocasse {@code estadoDesde} deixaria o carimbo envelhecer em
   * silêncio: a missão mudaria de estado com um marco temporal do estado ANTERIOR, e a varredura
   * expiraria uma missão recém-transicionada achando que ela está parada há horas. Como o único
   * chamador é {@code MissaoStateMachine}, que já recebe o instante da transição, exigi-lo na
   * assinatura torna o esquecimento impossível.
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
   * Pagamento saindo do pote na conclusão.
   *
   * <p>A guarda é de erro de programação, não a recusa que o usuário vê: quem chama já verificou o
   * saldo do pote sob o lock e respondeu 422 antes de escrever qualquer coisa. Se esta exceção
   * subir, o invariante "todo débito é precedido de verificação sob lock" deixou de valer, e um 500
   * alto é melhor que um pote negativo silencioso.
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
   * Exige reputação para aceitar.
   *
   * <p>Fora do construtor de propósito, e ainda assim sem virar setter genérico: nenhum DTO de
   * entrada alcança este método, então não reabre o mass assignment que o construtor de 22
   * parâmetros fechou. Chamado só na conversão de entrega falida, antes do primeiro {@code save}.
   */
  void exigirNivelMinimo(int nivel) {
    if (nivel < 1) {
      throw new IllegalArgumentException("Nível mínimo deve ser ao menos 1, veio " + nivel);
    }
    this.nivelMinimo = nivel;
  }
}
