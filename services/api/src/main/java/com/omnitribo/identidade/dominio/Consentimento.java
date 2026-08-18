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
@Table(name = "consentimento")
public class Consentimento {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(name = "usuario_id", nullable = false, updatable = false)
  private UUID usuarioId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 15)
  private TipoConsentimento tipo;

  @Column(nullable = false)
  private boolean concedido;

  @Column(name = "versao_texto", nullable = false, length = 20)
  private String versaoTexto;

  @Column(length = 45)
  private String ip;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private Instant criadoEm;

  protected Consentimento() {}

  public Consentimento(
      UUID id,
      UUID usuarioId,
      TipoConsentimento tipo,
      boolean concedido,
      String versaoTexto,
      String ip,
      Instant criadoEm) {
    this.id = id;
    this.usuarioId = usuarioId;
    this.tipo = tipo;
    this.concedido = concedido;
    this.versaoTexto = versaoTexto;
    this.ip = ip;
    this.criadoEm = criadoEm;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUsuarioId() {
    return usuarioId;
  }

  public TipoConsentimento getTipo() {
    return tipo;
  }

  public boolean isConcedido() {
    return concedido;
  }

  public String getVersaoTexto() {
    return versaoTexto;
  }

  public String getIp() {
    return ip;
  }

  public Instant getCriadoEm() {
    return criadoEm;
  }
}
