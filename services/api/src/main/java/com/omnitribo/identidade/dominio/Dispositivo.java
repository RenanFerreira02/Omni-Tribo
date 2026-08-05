package com.omnitribo.identidade.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dispositivo")
public class Dispositivo {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(name = "usuario_id", nullable = false, updatable = false)
  private UUID usuarioId;

  @Column(name = "push_token", nullable = false, length = 255, unique = true)
  private String pushToken;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private PlataformaDispositivo plataforma;

  @Column(name = "ultimo_visto_em", nullable = false)
  private Instant ultimoVistoEm;

  protected Dispositivo() {}

  public Dispositivo(
      UUID id,
      UUID usuarioId,
      String pushToken,
      PlataformaDispositivo plataforma,
      Instant ultimoVistoEm) {
    this.id = id;
    this.usuarioId = usuarioId;
    this.pushToken = pushToken;
    this.plataforma = plataforma;
    this.ultimoVistoEm = ultimoVistoEm;
  }

  public void registrarAcesso() {
    this.ultimoVistoEm = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getUsuarioId() {
    return usuarioId;
  }

  public String getPushToken() {
    return pushToken;
  }

  public void setPushToken(String pushToken) {
    this.pushToken = pushToken;
  }

  public PlataformaDispositivo getPlataforma() {
    return plataforma;
  }

  public Instant getUltimoVistoEm() {
    return ultimoVistoEm;
  }
}
