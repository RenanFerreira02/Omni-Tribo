package com.omnitribo.carteira.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

// APPEND-ONLY — ledger financeiro imutável após INSERT.
// @Immutable previne UPDATE acidental; REVOKE no banco é a proteção definitiva (V5).
// Correções são feitas por ESTORNO (nova linha com sinal oposto), nunca UPDATE.
@Immutable
@Entity
@Table(name = "lancamento")
public class Lancamento {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  // carteiraId: FK dentro do mesmo módulo — permitida
  @Column(name = "carteira_id", nullable = false, updatable = false)
  private UUID carteiraId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false, length = 7)
  private SinalLancamento sinal;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false, length = 30)
  private MotivoLancamento motivo;

  // numeric(12,2) → BigDecimal; nunca double
  @Column(name = "valor_brl", nullable = false, updatable = false, precision = 12, scale = 2)
  private BigDecimal valorBrl;

  @Column(name = "valor_tokens", nullable = false, updatable = false)
  private long valorTokens;

  // UUID puro, SEM FK e SEM @ManyToOne — fronteira crítica carteira→missoes.
  // Deliberado: viabiliza extração do módulo carteira em microsserviço futuro
  // sem dependência física de schema. Ver V5__carteira.sql e ADR 0001.
  @Column(name = "missao_id", updatable = false)
  private UUID missaoId;

  // contraparte dentro do mesmo módulo; FK permitida
  @Column(name = "contraparte_carteira_id", updatable = false)
  private UUID contraparteCarteiraId;

  @Column(
      name = "chave_idempotencia",
      nullable = false,
      updatable = false,
      unique = true,
      length = 100)
  private String chaveIdempotencia;

  @Column(name = "saldo_apos_brl", nullable = false, updatable = false, precision = 12, scale = 2)
  private BigDecimal saldoAposBrl;

  @Column(name = "saldo_apos_tokens", nullable = false, updatable = false)
  private long saldoAposTokens;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private Instant criadoEm;

  protected Lancamento() {}

  public Lancamento(
      UUID id,
      UUID carteiraId,
      SinalLancamento sinal,
      MotivoLancamento motivo,
      BigDecimal valorBrl,
      long valorTokens,
      UUID missaoId,
      UUID contraparteCarteiraId,
      String chaveIdempotencia,
      BigDecimal saldoAposBrl,
      long saldoAposTokens,
      Instant criadoEm) {
    this.id = id;
    this.carteiraId = carteiraId;
    this.sinal = sinal;
    this.motivo = motivo;
    this.valorBrl = valorBrl;
    this.valorTokens = valorTokens;
    this.missaoId = missaoId;
    this.contraparteCarteiraId = contraparteCarteiraId;
    this.chaveIdempotencia = chaveIdempotencia;
    this.saldoAposBrl = saldoAposBrl;
    this.saldoAposTokens = saldoAposTokens;
    this.criadoEm = criadoEm;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCarteiraId() {
    return carteiraId;
  }

  public SinalLancamento getSinal() {
    return sinal;
  }

  public MotivoLancamento getMotivo() {
    return motivo;
  }

  public BigDecimal getValorBrl() {
    return valorBrl;
  }

  public long getValorTokens() {
    return valorTokens;
  }

  public UUID getMissaoId() {
    return missaoId;
  }

  public UUID getContraparteCarteiraId() {
    return contraparteCarteiraId;
  }

  public String getChaveIdempotencia() {
    return chaveIdempotencia;
  }

  public BigDecimal getSaldoAposBrl() {
    return saldoAposBrl;
  }

  public long getSaldoAposTokens() {
    return saldoAposTokens;
  }

  public Instant getCriadoEm() {
    return criadoEm;
  }
}
