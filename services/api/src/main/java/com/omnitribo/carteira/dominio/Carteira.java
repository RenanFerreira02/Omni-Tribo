package com.omnitribo.carteira.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "carteira")
public class Carteira {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  // usuarioId: FK para usuario — carteira→identidade é permitido
  @Column(name = "usuario_id", nullable = false, updatable = false, unique = true)
  private UUID usuarioId;

  // numeric(12,2) → BigDecimal; nunca double (erros de ponto flutuante em BRL)
  @Column(name = "saldo_brl", nullable = false, precision = 12, scale = 2)
  private BigDecimal saldoBrl;

  @Column(name = "saldo_tokens", nullable = false)
  private long saldoTokens;

  @Version
  @Column(nullable = false)
  private int versao;

  protected Carteira() {}

  public Carteira(UUID id, UUID usuarioId) {
    this.id = id;
    this.usuarioId = usuarioId;
    this.saldoBrl = BigDecimal.ZERO;
    this.saldoTokens = 0L;
  }

  public void creditar(BigDecimal valorBrl, long valorTokens) {
    this.saldoBrl = this.saldoBrl.add(valorBrl);
    this.saldoTokens += valorTokens;
  }

  public void debitar(BigDecimal valorBrl, long valorTokens) {
    this.saldoBrl = this.saldoBrl.subtract(valorBrl);
    this.saldoTokens -= valorTokens;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUsuarioId() {
    return usuarioId;
  }

  public BigDecimal getSaldoBrl() {
    return saldoBrl;
  }

  public long getSaldoTokens() {
    return saldoTokens;
  }

  public int getVersao() {
    return versao;
  }
}
