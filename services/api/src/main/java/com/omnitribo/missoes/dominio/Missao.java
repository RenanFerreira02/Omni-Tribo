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

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 15)
  private StatusMissao status;

  @Column(name = "xp_recompensa", nullable = false)
  private int xpRecompensa;

  // numeric(12,2) → BigDecimal; nunca double (erros de ponto flutuante em BRL)
  @Column(name = "valor_brl", nullable = false, precision = 12, scale = 2)
  private BigDecimal valorBrl;

  @Column(name = "tokens_recompensa", nullable = false)
  private long tokensRecompensa;

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

  @Version
  @Column(nullable = false)
  private int versao;

  protected Missao() {}

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

  public void setStatus(StatusMissao status) {
    this.status = status;
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

  public int getVersao() {
    return versao;
  }
}
