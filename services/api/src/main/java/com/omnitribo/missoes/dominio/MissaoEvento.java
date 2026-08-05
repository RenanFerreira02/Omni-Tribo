package com.omnitribo.missoes.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// APPEND-ONLY — trilha do ciclo de vida da missão; nunca se altera um evento registrado.
@Immutable
@Entity
@Table(name = "missao_evento")
public class MissaoEvento {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  // missaoId: FK dentro do mesmo módulo — permitida
  @Column(name = "missao_id", nullable = false, updatable = false)
  private UUID missaoId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false, length = 30)
  private TipoMissaoEvento tipo;

  // atorId: FK para usuario — missoes→identidade é permitido; nullable (eventos do sistema)
  @Column(name = "ator_id", updatable = false)
  private UUID atorId;

  @Column(name = "de_status", updatable = false, length = 15)
  private String deStatus;

  @Column(name = "para_status", updatable = false, length = 15)
  private String paraStatus;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(updatable = false, columnDefinition = "jsonb")
  private String payload;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private Instant criadoEm;

  protected MissaoEvento() {}

  public MissaoEvento(
      UUID id,
      UUID missaoId,
      TipoMissaoEvento tipo,
      UUID atorId,
      StatusMissao deStatus,
      StatusMissao paraStatus,
      String payload,
      Instant criadoEm) {
    this.id = id;
    this.missaoId = missaoId;
    this.tipo = tipo;
    this.atorId = atorId;
    this.deStatus = deStatus != null ? deStatus.name() : null;
    this.paraStatus = paraStatus != null ? paraStatus.name() : null;
    this.payload = payload;
    this.criadoEm = criadoEm;
  }

  public UUID getId() {
    return id;
  }

  public UUID getMissaoId() {
    return missaoId;
  }

  public TipoMissaoEvento getTipo() {
    return tipo;
  }

  public UUID getAtorId() {
    return atorId;
  }

  public String getDeStatus() {
    return deStatus;
  }

  public String getParaStatus() {
    return paraStatus;
  }

  public String getPayload() {
    return payload;
  }

  public Instant getCriadoEm() {
    return criadoEm;
  }
}
