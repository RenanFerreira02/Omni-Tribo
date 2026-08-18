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

  // 64 desde a V20: um `traceparent` do W3C tem 55 caracteres, e truncá-lo em 36 cortava o trace-id
  // ao meio — destruindo exatamente a correlação que é a única razão desta coluna existir.
  @Column(name = "correlation_id", updatable = false, length = 64)
  private String correlationId;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private Instant criadoEm;

  protected Auditoria() {}

  /**
   * Trunca os campos de origem do cliente, e faz isso AQUI de propósito.
   *
   * <p>Três lugares gravam auditoria ({@code AuditoriaAspecto}, {@code AutenticacaoService} e
   * {@code BloqueioLoginService}) e todos passam por este construtor — é o único ponto que cobre os
   * três, e o quarto gravador que aparecer. Truncar em cada chamador deixaria o próximo
   * desprotegido.
   *
   * <p>Sem isso, um {@code X-Forwarded-For} ou {@code User-Agent} longo demais estourava a coluna
   * com SQLState 22001 durante o flush. E como {@code auditoriaService.gravar} roda DENTRO da
   * transação de login, o efeito era o login inteiro responder <b>500</b>, sem autenticação
   * nenhuma, para qualquer um que mandasse um header comprido. Nos endpoints autenticados era pior:
   * o aspecto grava depois do commit, então uma transferência movia o dinheiro E devolvia 500.
   *
   * <p>{@code ddl-auto: validate} não protege disso — ele confere que a coluna existe com o tipo
   * declarado, não impõe o limite em runtime.
   */
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
    this.entidadeId = limitar(entidadeId, 255);
    this.ip = limitar(ip, 45);
    this.userAgent = limitar(userAgent, 500);
    this.correlationId = limitar(correlationId, 64);
    this.criadoEm = criadoEm;
  }

  /** {@code acao} e {@code entidade} ficam de fora: são constantes do código, não entrada. */
  private static String limitar(String valor, int maximo) {
    if (valor == null || valor.length() <= maximo) {
      return valor;
    }
    return valor.substring(0, maximo);
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
