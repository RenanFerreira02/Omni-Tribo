package com.omnitribo.logistica.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "entrega_falida")
public class EntregaFalida {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(nullable = false, length = 100)
  private String transportadora;

  @Column(name = "codigo_rastreio", nullable = false, length = 100)
  private String codigoRastreio;

  @Column(nullable = false, length = 500)
  private String motivo;

  // pontoCustodiaId: FK dentro do mesmo módulo — permitida
  @Column(name = "ponto_custodia_id", nullable = false)
  private UUID pontoCustodiaId;

  // UUID puro, sem FK — fronteira logistica→missoes; ver V6__logistica.sql
  @Column(name = "missao_id")
  private UUID missaoId;

  @Column(name = "recebido_em", nullable = false, updatable = false)
  private Instant recebidoEm;

  @Column(name = "assinatura_verificada", nullable = false)
  private boolean assinaturaVerificada;

  // preenchido quando a entrega falida é convertida em missão de retirada
  @Column(name = "convertida_em")
  private Instant convertidaEm;

  protected EntregaFalida() {}

  public UUID getId() {
    return id;
  }

  public String getTransportadora() {
    return transportadora;
  }

  public String getCodigoRastreio() {
    return codigoRastreio;
  }

  public String getMotivo() {
    return motivo;
  }

  public UUID getPontoCustodiaId() {
    return pontoCustodiaId;
  }

  public UUID getMissaoId() {
    return missaoId;
  }

  public void setMissaoId(UUID missaoId) {
    this.missaoId = missaoId;
  }

  public Instant getRecebidoEm() {
    return recebidoEm;
  }

  public boolean isAssinaturaVerificada() {
    return assinaturaVerificada;
  }

  public Instant getConvertidaEm() {
    return convertidaEm;
  }

  public void converter(UUID missaoId) {
    this.missaoId = missaoId;
    this.convertidaEm = Instant.now();
  }
}
