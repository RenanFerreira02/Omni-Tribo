package com.omnitribo.compartilhado.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alerta")
public class Alerta {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  // usuarioId: FK para usuario — compartilhado→identidade é permitido; null = alerta global
  @Column(name = "usuario_id")
  private UUID usuarioId;

  @Column(nullable = false, length = 50)
  private String tipo;

  @Column(nullable = false, length = 200)
  private String titulo;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String corpo;

  // UUID puro, sem FK — fronteira compartilhado→missoes; ver V7__compartilhado.sql
  @Column(name = "missao_id")
  private UUID missaoId;

  @Column(nullable = false)
  private boolean lido;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private Instant criadoEm;

  protected Alerta() {}

  public Alerta(
      UUID id,
      UUID usuarioId,
      String tipo,
      String titulo,
      String corpo,
      UUID missaoId,
      Instant criadoEm) {
    this.id = id;
    this.usuarioId = usuarioId;
    this.tipo = tipo;
    this.titulo = titulo;
    this.corpo = corpo;
    this.missaoId = missaoId;
    this.lido = false;
    this.criadoEm = criadoEm;
  }

  public void marcarLido() {
    this.lido = true;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUsuarioId() {
    return usuarioId;
  }

  public String getTipo() {
    return tipo;
  }

  public String getTitulo() {
    return titulo;
  }

  public String getCorpo() {
    return corpo;
  }

  public UUID getMissaoId() {
    return missaoId;
  }

  public boolean isLido() {
    return lido;
  }

  public Instant getCriadoEm() {
    return criadoEm;
  }
}
