package com.omnitribo.compartilhado.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// Transactional Outbox Pattern: eventos gravados na mesma transação da mutação de estado.
// Um processo separado (a implementar em fases futuras) lê e publica para consumidores.
@Entity
@Table(name = "outbox")
public class Outbox {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(name = "tipo_evento", nullable = false, length = 100)
  private String tipoEvento;

  @Column(name = "agregado_id", nullable = false)
  private UUID agregadoId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String payload;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private Instant criadoEm;

  @Column(name = "publicado_em")
  private Instant publicadoEm;

  @Column(nullable = false)
  private int tentativas;

  protected Outbox() {}

  public Outbox(UUID id, String tipoEvento, UUID agregadoId, String payload, Instant criadoEm) {
    this.id = id;
    this.tipoEvento = tipoEvento;
    this.agregadoId = agregadoId;
    this.payload = payload;
    this.criadoEm = criadoEm;
    this.tentativas = 0;
  }

  public void marcarPublicado() {
    this.publicadoEm = Instant.now();
  }

  public void incrementarTentativas() {
    this.tentativas++;
  }

  public UUID getId() {
    return id;
  }

  public String getTipoEvento() {
    return tipoEvento;
  }

  public UUID getAgregadoId() {
    return agregadoId;
  }

  public String getPayload() {
    return payload;
  }

  public Instant getCriadoEm() {
    return criadoEm;
  }

  public Instant getPublicadoEm() {
    return publicadoEm;
  }

  public int getTentativas() {
    return tentativas;
  }
}
