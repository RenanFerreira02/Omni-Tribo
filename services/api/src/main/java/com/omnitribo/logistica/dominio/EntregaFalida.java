package com.omnitribo.logistica.dominio;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.locationtech.jts.geom.Point;

/**
 * Uma entrega que falhou e foi depositada num ponto de custódia.
 *
 * <p>É o registro que dá nome ao challenge: o fato bruto reportado pela transportadora, do qual
 * nasce a missão comunitária de retirada. A tabela existe desde a V6; a V21 acrescentou o que
 * faltava para a conversão acontecer (peso, volume, endereço de destino) e a chave que torna o
 * webhook idempotente.
 *
 * <p><b>Três estados, distinguidos por duas colunas nuláveis e não por um enum.</b>
 *
 * <ul>
 *   <li>RECEBIDA — {@code missaoId} nulo, {@code recusadaEm} nulo. Está no ponto, ocupa vaga.
 *   <li>CONVERTIDA — {@code missaoId} preenchido. Ocupa vaga até a missão concluir.
 *   <li>RECUSADA — {@code recusadaEm} preenchido. Nunca entrou no ponto, não ocupa vaga.
 * </ul>
 *
 * <p>Uma coluna {@code status} seria mais legível e não sobreviveu à realidade das migrations: os
 * seeds V901/V903 já foram aplicados em bancos de dev e não podem ser editados (mudar seed aplicado
 * quebra o checksum), e como a faixa 900+ roda DEPOIS do schema, um DEFAULT não teria como
 * distinguir convertida de pendente. Ver o comentário da V21.
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

  /**
   * Instante em que a encomenda SAIU da custódia — carimbado na conclusão da missão, junto com o
   * decremento da ocupação. O comentário original da V6 dizia "quando vira missão"; a V21 corrigiu
   * o significado e o comentário da coluna.
   */
  @Column(name = "convertida_em")
  private Instant convertidaEm;

  @Column(name = "recusada_em")
  private Instant recusadaEm;

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

  protected EntregaFalida() {}

  public EntregaFalida(UUID id, DadosEntregaFalida dados, Instant recebidoEm) {
    this.id = id;
    this.transportadora = dados.transportadora();
    this.codigoRastreio = dados.codigoRastreio();
    this.motivo = dados.motivo();
    this.pontoCustodiaId = dados.pontoCustodiaId();
    this.recebidoEm = recebidoEm;
    // A assinatura HMAC já foi conferida no filtro de borda — nenhuma requisição chega aqui sem
    // ela. A coluna existe desde a V6 e é a evidência gravada de que a verificação aconteceu.
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
  }

  /** Vincula a missão criada. Chamado só na conversão, sob o lock do ponto. */
  public void vincularMissao(UUID missaoId) {
    if (this.recusadaEm != null) {
      throw new IllegalStateException("Entrega recusada não gera missão: " + id);
    }
    this.missaoId = missaoId;
  }

  /**
   * Marca a recusa por falta de vaga.
   *
   * <p>O fato é gravado mesmo assim: a transportadora precisa saber onde o pacote dela parou, e um
   * ponto cronicamente lotado é exatamente o dado que justifica abrir outro ponto no bairro.
   * Recusada não ocupa vaga — {@code MigracaoTest} confere isso.
   */
  public void recusar(Instant quando) {
    if (this.missaoId != null) {
      throw new IllegalStateException("Entrega já convertida não pode ser recusada: " + id);
    }
    this.recusadaEm = quando;
  }

  /** Baixa da custódia: a missão concluiu e a encomenda saiu do ponto. */
  public void darBaixa(Instant quando) {
    this.convertidaEm = quando;
  }

  public boolean foiRecusada() {
    return recusadaEm != null;
  }

  public boolean foiConvertida() {
    return missaoId != null;
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
