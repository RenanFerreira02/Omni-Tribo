package com.omnitribo.logistica.dominio;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.locationtech.jts.geom.Point;

/**
 * Uma entrega que falhou e foi depositada num ponto de custódia — o fato bruto da transportadora,
 * do qual nasce a missão de retirada.
 *
 * <p>Três estados por duas colunas nuláveis, não por enum (os seeds aplicados não podiam ganhar a
 * coluna):
 *
 * <ul>
 *   <li>RECEBIDA — {@code missaoId} e {@code recusadaEm} nulos; ocupa vaga.
 *   <li>CONVERTIDA — {@code missaoId} preenchido; ocupa vaga até a missão concluir.
 *   <li>RECUSADA — {@code recusadaEm} preenchido; não ocupa vaga.
 * </ul>
 */
@Entity
@Table(name = "entrega_falida")
public class EntregaFalida {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(nullable = false, updatable = false, length = 100)
  private String transportadora;

  @Column(name = "codigo_rastreio", nullable = false, updatable = false, length = 100)
  private String codigoRastreio;

  @Column(nullable = false, length = 500)
  private String motivo;

  /** FK dentro do próprio módulo — permitida. */
  @Column(name = "ponto_custodia_id", nullable = false, updatable = false)
  private UUID pontoCustodiaId;

  /** UUID puro, sem FK: fronteira logistica→missoes, deliberada desde a V6. */
  @Column(name = "missao_id")
  private UUID missaoId;

  @Column(name = "recebido_em", nullable = false, updatable = false)
  private Instant recebidoEm;

  @Column(name = "assinatura_verificada", nullable = false)
  private boolean assinaturaVerificada;

  /** Instante em que a encomenda SAIU da custódia, carimbado na conclusão da missão. */
  @Column(name = "convertida_em")
  private Instant convertidaEm;

  @Column(name = "recusada_em")
  private Instant recusadaEm;

  /**
   * Motivo da recusa — sempre junto de {@link #recusadaEm} ({@code
   * ck_entrega_falida_recusa_coerente}).
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "motivo_recusa", length = 20)
  private MotivoRecusa motivoRecusa;

  @Column(name = "peso_kg", precision = 6, scale = 2)
  private BigDecimal pesoKg;

  @Column(name = "volume_l", precision = 8, scale = 2)
  private BigDecimal volumeL;

  @Column(name = "valor_ofertado_brl", precision = 12, scale = 2)
  private BigDecimal valorOfertadoBrl;

  @Column(columnDefinition = "geography(POINT,4326)")
  private Point destino;

  @Column(name = "destino_cep", length = 8)
  private String destinoCep;

  @Column(name = "destino_logradouro", length = 200)
  private String destinoLogradouro;

  @Column(name = "destino_bairro", length = 100)
  private String destinoBairro;

  @Column(name = "destino_cidade", length = 100)
  private String destinoCidade;

  @Column(name = "destino_uf", length = 2)
  private String destinoUf;

  // ─── Características informadas pela transportadora (opcionais; o que faltar é imputado) ───

  @Column(name = "janela_hora_inicio")
  private Short janelaHoraInicio;

  @Column(name = "tipo_endereco", length = 12)
  @Enumerated(EnumType.STRING)
  private TipoEndereco tipoEndereco;

  @Column(name = "tentativas_anteriores")
  private Short tentativasAnteriores;

  // ─── O que o modelo previu — gravado para a validação futura contra dados reais (ADR 0022) ───

  @Column(name = "risco_probabilidade", precision = 5, scale = 4)
  private BigDecimal riscoProbabilidade;

  @Column(name = "risco_faixa", length = 5)
  @Enumerated(EnumType.STRING)
  private FaixaRisco riscoFaixa;

  @Column(name = "risco_multiplicador", precision = 4, scale = 2)
  private BigDecimal riscoMultiplicador;

  @Column(name = "risco_versao_modelo")
  private Integer riscoVersaoModelo;

  protected EntregaFalida() {}

  public EntregaFalida(UUID id, DadosEntregaFalida dados, Instant recebidoEm) {
    this.id = id;
    this.transportadora = dados.transportadora();
    this.codigoRastreio = dados.codigoRastreio();
    this.motivo = dados.motivo();
    this.pontoCustodiaId = dados.pontoCustodiaId();
    this.recebidoEm = recebidoEm;
    // O HMAC já foi conferido no filtro de borda; a coluna é a evidência gravada.
    this.assinaturaVerificada = true;
    this.pesoKg = dados.pesoKg();
    this.volumeL = dados.volumeL();
    this.valorOfertadoBrl = dados.valorOfertadoBrl();
    this.destino = dados.destino();
    this.destinoCep = dados.cep();
    this.destinoLogradouro = dados.logradouro();
    this.destinoBairro = dados.bairro();
    this.destinoCidade = dados.cidade();
    this.destinoUf = dados.uf();
    this.janelaHoraInicio = dados.janelaHoraInicio();
    this.tipoEndereco = dados.tipoEndereco();
    this.tentativasAnteriores = dados.tentativasAnteriores();
  }

  /**
   * Congela o que o modelo previu, uma vez, na conversão. O mesmo multiplicador vai para a missão;
   * aqui ele fica junto das características que o produziram, para auditar o modelo depois.
   */
  public void congelarRisco(
      BigDecimal probabilidade, FaixaRisco faixa, BigDecimal multiplicador, int versaoModelo) {
    this.riscoProbabilidade = probabilidade;
    this.riscoFaixa = faixa;
    this.riscoMultiplicador = multiplicador;
    this.riscoVersaoModelo = versaoModelo;
  }

  public Short getJanelaHoraInicio() {
    return janelaHoraInicio;
  }

  public TipoEndereco getTipoEndereco() {
    return tipoEndereco;
  }

  public Short getTentativasAnteriores() {
    return tentativasAnteriores;
  }

  public BigDecimal getRiscoProbabilidade() {
    return riscoProbabilidade;
  }

  public FaixaRisco getRiscoFaixa() {
    return riscoFaixa;
  }

  public BigDecimal getRiscoMultiplicador() {
    return riscoMultiplicador;
  }

  public Integer getRiscoVersaoModelo() {
    return riscoVersaoModelo;
  }

  /** Vincula a missão criada. Chamado só na conversão, sob o lock do ponto. */
  public void vincularMissao(UUID missaoId) {
    if (this.recusadaEm != null) {
      throw new IllegalStateException("Entrega recusada não gera missão: " + id);
    }
    this.missaoId = missaoId;
  }

  /**
   * Marca a recusa, com motivo obrigatório. O fato é gravado: a transportadora precisa saber onde o
   * pacote parou. Recusada não ocupa vaga.
   */
  public void recusar(Instant quando, MotivoRecusa motivo) {
    if (this.missaoId != null) {
      throw new IllegalStateException("Entrega já convertida não pode ser recusada: " + id);
    }
    this.recusadaEm = quando;
    this.motivoRecusa = motivo;
  }

  /** Baixa da custódia: a missão concluiu e a encomenda saiu do ponto. */
  public void darBaixa(Instant quando) {
    this.convertidaEm = quando;
  }

  public boolean foiRecusada() {
    return recusadaEm != null;
  }

  public MotivoRecusa getMotivoRecusa() {
    return motivoRecusa;
  }

  public boolean saiuDaCustodia() {
    return convertidaEm != null;
  }

  public UUID getId() {
    return id;
  }

  public String getTransportadora() {
    return transportadora;
  }

  public String getCodigoRastreio() {
    return codigoRastreio;
  }

  public String getMotivo() {
    return motivo;
  }

  public UUID getPontoCustodiaId() {
    return pontoCustodiaId;
  }

  public UUID getMissaoId() {
    return missaoId;
  }

  public Instant getRecebidoEm() {
    return recebidoEm;
  }

  public boolean isAssinaturaVerificada() {
    return assinaturaVerificada;
  }

  public Instant getConvertidaEm() {
    return convertidaEm;
  }

  public Instant getRecusadaEm() {
    return recusadaEm;
  }

  public BigDecimal getPesoKg() {
    return pesoKg;
  }

  public BigDecimal getVolumeL() {
    return volumeL;
  }

  public BigDecimal getValorOfertadoBrl() {
    return valorOfertadoBrl;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Point de JTS é imutável após construção; cópia defensiva sem benefício")
  public Point getDestino() {
    return destino;
  }

  public String getDestinoCep() {
    return destinoCep;
  }

  public String getDestinoLogradouro() {
    return destinoLogradouro;
  }

  public String getDestinoBairro() {
    return destinoBairro;
  }

  public String getDestinoCidade() {
    return destinoCidade;
  }

  public String getDestinoUf() {
    return destinoUf;
  }
}
