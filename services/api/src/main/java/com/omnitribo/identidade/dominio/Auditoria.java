package com.omnitribo.identidade.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

// APPEND-ONLY — @Immutable impede que o Hibernate gere UPDATE silencioso.
// A proteção definitiva está no banco: REVOKE UPDATE, DELETE FROM omnitribo_app (V2).
@Immutable
@Entity
@Table(name = "auditoria")
public class Auditoria {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  // null para ações anônimas (tentativas de login, acesso sem autenticação)
  @Column(name = "ator_id", updatable = false)
  private UUID atorId;

  @Column(nullable = false, updatable = false, length = 100)
  private String acao;

  @Column(nullable = false, updatable = false, length = 100)
  private String entidade;

  @Column(name = "entidade_id", updatable = false, length = 255)
  private String entidadeId;

  @Column(updatable = false, length = 45)
  private String ip;

  @Column(name = "user_agent", updatable = false, length = 500)
  private String userAgent;

  @Column(name = "correlation_id", updatable = false, length = 36)
  private String correlationId;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private Instant criadoEm;

  protected Auditoria() {}

  public Auditoria(
      UUID id,
      UUID atorId,
      String acao,
      String entidade,
      String entidadeId,
      String ip,
      String userAgent,
      String correlationId,
      Instant criadoEm) {
    this.id = id;
    this.atorId = atorId;
    this.acao = acao;
    this.entidade = entidade;
    this.entidadeId = entidadeId;
    this.ip = ip;
    this.userAgent = userAgent;
    this.correlationId = correlationId;
    this.criadoEm = criadoEm;
  }

  public UUID getId() {
    return id;
  }

  public UUID getAtorId() {
    return atorId;
  }

  public String getAcao() {
    return acao;
  }

  public String getEntidade() {
    return entidade;
  }

  public String getEntidadeId() {
    return entidadeId;
  }

  public String getIp() {
    return ip;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public Instant getCriadoEm() {
    return criadoEm;
  }
}
