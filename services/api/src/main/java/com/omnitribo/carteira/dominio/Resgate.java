package com.omnitribo.carteira.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * O registro de uma QUEIMA de token.
 *
 * <p>Cada linha aqui tem um lançamento correspondente com motivo {@link MotivoLancamento#RESGATE},
 * que debita e não credita ninguém. É o único caminho pelo qual token sai da economia.
 */
@Entity
@Table(name = "resgate")
public class Resgate {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(name = "usuario_id", nullable = false, updatable = false)
  private UUID usuarioId;

  @Column(name = "beneficio_id", nullable = false, updatable = false)
  private UUID beneficioId;

  /** O custo COBRADO, congelado. Ver o javadoc de {@link Beneficio#getCustoTokens()}. */
  @Column(name = "custo_tokens", nullable = false, updatable = false)
  private long custoTokens;

  @Column(name = "codigo_retirada", nullable = false, updatable = false, length = 8)
  private String codigoRetirada;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private StatusResgate status;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private Instant criadoEm;

  @Column(name = "utilizado_em")
  private Instant utilizadoEm;

  protected Resgate() {}

  public Resgate(
      UUID id,
      UUID usuarioId,
      UUID beneficioId,
      long custoTokens,
      String codigoRetirada,
      Instant criadoEm) {
    this.id = id;
    this.usuarioId = usuarioId;
    this.beneficioId = beneficioId;
    this.custoTokens = custoTokens;
    this.codigoRetirada = codigoRetirada;
    this.status = StatusResgate.PENDENTE;
    this.criadoEm = criadoEm;
  }

  /**
   * O parceiro entregou o benefício.
   *
   * <p>Idempotente: dar baixa num resgate já utilizado não é erro, é o retry de quem não recebeu a
   * resposta. Devolve {@code false} quando nada mudou, para o serviço distinguir os dois casos sem
   * precisar reler o estado.
   *
   * <p>Status e carimbo mudam SEMPRE juntos — {@code ck_resgate_utilizado_coerente} (V25) recusa
   * uma metade sem a outra.
   */
  public boolean darBaixa(Instant quando) {
    if (this.status == StatusResgate.UTILIZADO) {
      return false;
    }
    this.status = StatusResgate.UTILIZADO;
    this.utilizadoEm = quando;
    return true;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUsuarioId() {
    return usuarioId;
  }

  public UUID getBeneficioId() {
    return beneficioId;
  }

  public long getCustoTokens() {
    return custoTokens;
  }

  public String getCodigoRetirada() {
    return codigoRetirada;
  }

  public StatusResgate getStatus() {
    return status;
  }

  public Instant getCriadoEm() {
    return criadoEm;
  }

  public Instant getUtilizadoEm() {
    return utilizadoEm;
  }
}
