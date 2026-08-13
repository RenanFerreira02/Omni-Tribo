package com.omnitribo.identidade.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_token")
public class RefreshToken {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(name = "usuario_id", nullable = false, updatable = false)
  private UUID usuarioId;

  @Column(name = "token_hash", nullable = false, length = 255)
  private String tokenHash;

  // familiaId agrupa tokens de uma mesma sessão para detecção de reuso (token rotation)
  @Column(name = "familia_id", nullable = false)
  private UUID familiaId;

  @Column(name = "expira_em", nullable = false)
  private Instant expiraEm;

  @Column(name = "revogado_em")
  private Instant revogadoEm;

  // UUID puro sem FK: evita constraint circular na rotação; ver V2__identidade.sql
  @Column(name = "substituido_por")
  private UUID substituidoPor;

  protected RefreshToken() {}

  public RefreshToken(UUID id, UUID usuarioId, String tokenHash, UUID familiaId, Instant expiraEm) {
    this.id = id;
    this.usuarioId = usuarioId;
    this.tokenHash = tokenHash;
    this.familiaId = familiaId;
    this.expiraEm = expiraEm;
  }

  public boolean estaValido() {
    return revogadoEm == null && Instant.now().isBefore(expiraEm);
  }

  public void revogar(UUID substituidoPorId) {
    this.revogadoEm = Instant.now();
    this.substituidoPor = substituidoPorId;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUsuarioId() {
    return usuarioId;
  }

  public UUID getFamiliaId() {
    return familiaId;
  }

  public Instant getRevogadoEm() {
    return revogadoEm;
  }
}
