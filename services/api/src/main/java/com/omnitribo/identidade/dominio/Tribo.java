package com.omnitribo.identidade.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tribo")
public class Tribo {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(nullable = false, length = 100)
  private String nome;

  @Column(nullable = false, length = 100)
  private String bairro;

  @Column(name = "criada_em", nullable = false, updatable = false)
  private Instant criadaEm;

  protected Tribo() {}

  public Tribo(UUID id, String nome, String bairro, Instant criadaEm) {
    this.id = id;
    this.nome = nome;
    this.bairro = bairro;
    this.criadaEm = criadaEm;
  }

  public UUID getId() {
    return id;
  }

  public String getNome() {
    return nome;
  }

  public String getBairro() {
    return bairro;
  }

  public Instant getCriadaEm() {
    return criadaEm;
  }
}
