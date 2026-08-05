package com.omnitribo.logistica.dominio;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "ponto_custodia")
public class PontoCustodia {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(nullable = false, unique = true, length = 20)
  private String codigo;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private TipoPontoCustodia tipo;

  @Column(nullable = false, length = 100)
  private String apelido;

  @Column(nullable = false, columnDefinition = "geography(POINT,4326)")
  private Point ponto;

  // triboId: FK para tribo — logistica→identidade é permitido
  @Column(name = "tribo_id")
  private UUID triboId;

  @Column(nullable = false)
  private int capacidade;

  @Column(nullable = false)
  private int ocupacao;

  @Column(nullable = false)
  private boolean ativo;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private Instant criadoEm;

  protected PontoCustodia() {}

  public UUID getId() {
    return id;
  }

  public String getCodigo() {
    return codigo;
  }

  public TipoPontoCustodia getTipo() {
    return tipo;
  }

  public String getApelido() {
    return apelido;
  }

  public void setApelido(String apelido) {
    this.apelido = apelido;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Point de JTS é imutável após construção; cópia defensiva sem benefício")
  public Point getPonto() {
    return ponto;
  }

  public UUID getTriboId() {
    return triboId;
  }

  public int getCapacidade() {
    return capacidade;
  }

  public int getOcupacao() {
    return ocupacao;
  }

  public void incrementarOcupacao() {
    this.ocupacao++;
  }

  public void decrementarOcupacao() {
    if (this.ocupacao > 0) this.ocupacao--;
  }

  public boolean isAtivo() {
    return ativo;
  }

  public void setAtivo(boolean ativo) {
    this.ativo = ativo;
  }

  public Instant getCriadoEm() {
    return criadoEm;
  }
}
