package com.omnitribo.geolocalizacao.dominio;

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
import org.hibernate.annotations.Immutable;
import org.locationtech.jts.geom.Point;

// APPEND-ONLY — grava check-ins válidos e rejeitados para análise de fraude.
// @Immutable previne UPDATE acidental; REVOKE no banco é a proteção definitiva (V4).
@Immutable
@Entity
@Table(name = "checkin")
public class Checkin {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  // UUID puro, sem FK — fronteira geolocalizacao→missoes; ver V4__geolocalizacao.sql
  @Column(name = "missao_id", nullable = false, updatable = false)
  private UUID missaoId;

  // usuarioId: FK para usuario — módulo→identidade é permitido
  @Column(name = "usuario_id", nullable = false, updatable = false)
  private UUID usuarioId;

  @Column(nullable = false, updatable = false, columnDefinition = "geography(POINT,4326)")
  private Point ponto;

  @Column(name = "acuracia_m", nullable = false, updatable = false, precision = 10, scale = 2)
  private BigDecimal acuraciaM;

  @Column(name = "distancia_alvo_m", nullable = false, updatable = false, precision = 10, scale = 2)
  private BigDecimal distanciaAlvoM;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false, length = 5)
  private MetodoCheckin metodo;

  @Column(name = "mock_detectado", nullable = false, updatable = false)
  private boolean mockDetectado;

  // null no primeiro check-in ou quando não há check-in anterior próximo
  @Column(name = "velocidade_implicita_kmh", updatable = false, precision = 10, scale = 2)
  private BigDecimal velocidadeImplicitaKmh;

  @Column(nullable = false, updatable = false)
  private boolean valido;

  // null quando valido=true; preenchido quando valido=false
  @Column(name = "motivo_rejeicao", updatable = false, length = 500)
  private String motivoRejeicao;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private Instant criadoEm;

  protected Checkin() {}

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Point de JTS é imutável após construção; cópia defensiva sem benefício")
  public Checkin(
      UUID id,
      UUID missaoId,
      UUID usuarioId,
      Point ponto,
      BigDecimal acuraciaM,
      BigDecimal distanciaAlvoM,
      MetodoCheckin metodo,
      boolean mockDetectado,
      BigDecimal velocidadeImplicitaKmh,
      boolean valido,
      String motivoRejeicao,
      Instant criadoEm) {
    this.id = id;
    this.missaoId = missaoId;
    this.usuarioId = usuarioId;
    this.ponto = ponto;
    this.acuraciaM = acuraciaM;
    this.distanciaAlvoM = distanciaAlvoM;
    this.metodo = metodo;
    this.mockDetectado = mockDetectado;
    this.velocidadeImplicitaKmh = velocidadeImplicitaKmh;
    this.valido = valido;
    this.motivoRejeicao = motivoRejeicao;
    this.criadoEm = criadoEm;
  }

  public UUID getId() {
    return id;
  }

  public UUID getMissaoId() {
    return missaoId;
  }

  public UUID getUsuarioId() {
    return usuarioId;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Point de JTS é imutável após construção; cópia defensiva sem benefício")
  public Point getPonto() {
    return ponto;
  }

  public BigDecimal getAcuraciaM() {
    return acuraciaM;
  }

  public BigDecimal getDistanciaAlvoM() {
    return distanciaAlvoM;
  }

  public MetodoCheckin getMetodo() {
    return metodo;
  }

  public boolean isMockDetectado() {
    return mockDetectado;
  }

  public BigDecimal getVelocidadeImplicitaKmh() {
    return velocidadeImplicitaKmh;
  }

  public boolean isValido() {
    return valido;
  }

  public String getMotivoRejeicao() {
    return motivoRejeicao;
  }

  public Instant getCriadoEm() {
    return criadoEm;
  }
}
