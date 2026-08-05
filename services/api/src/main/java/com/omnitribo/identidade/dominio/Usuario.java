package com.omnitribo.identidade.dominio;

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

@Entity
@Table(name = "usuario")
public class Usuario {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(nullable = false, length = 100)
  private String nome;

  @Column(nullable = false, length = 255, unique = true)
  private String email;

  @Column(name = "senha_hash", nullable = false, length = 255)
  private String senhaHash;

  @Column(nullable = false, length = 50, unique = true)
  private String handle;

  // triboId: FK para tribo — mesmo módulo identidade
  @Column(name = "tribo_id")
  private UUID triboId;

  @Column(nullable = false)
  private long xp;

  @Column(nullable = false)
  private int nivel;

  @Column(nullable = false)
  private int streak;

  @Column(nullable = false, precision = 2, scale = 1)
  private BigDecimal rating;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private PapelUsuario papel;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private StatusUsuario status;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private Instant criadoEm;

  @Column(name = "atualizado_em", nullable = false)
  private Instant atualizadoEm;

  @Version
  @Column(nullable = false)
  private int versao;

  protected Usuario() {}

  public UUID getId() {
    return id;
  }

  public String getNome() {
    return nome;
  }

  public void setNome(String nome) {
    this.nome = nome;
  }

  public String getEmail() {
    return email;
  }

  public String getSenhaHash() {
    return senhaHash;
  }

  public void setSenhaHash(String senhaHash) {
    this.senhaHash = senhaHash;
  }

  public String getHandle() {
    return handle;
  }

  public UUID getTriboId() {
    return triboId;
  }

  public void setTriboId(UUID triboId) {
    this.triboId = triboId;
  }

  public long getXp() {
    return xp;
  }

  public void adicionarXp(long quantidade) {
    this.xp += quantidade;
  }

  public int getNivel() {
    return nivel;
  }

  public void setNivel(int nivel) {
    this.nivel = nivel;
  }

  public int getStreak() {
    return streak;
  }

  public void setStreak(int streak) {
    this.streak = streak;
  }

  public BigDecimal getRating() {
    return rating;
  }

  public void setRating(BigDecimal rating) {
    this.rating = rating;
  }

  public PapelUsuario getPapel() {
    return papel;
  }

  public StatusUsuario getStatus() {
    return status;
  }

  public void setStatus(StatusUsuario status) {
    this.status = status;
  }

  public Instant getCriadoEm() {
    return criadoEm;
  }

  public Instant getAtualizadoEm() {
    return atualizadoEm;
  }

  public void setAtualizadoEm(Instant atualizadoEm) {
    this.atualizadoEm = atualizadoEm;
  }

  public int getVersao() {
    return versao;
  }
}
