package com.omnitribo.compartilhado.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// Transactional Outbox Pattern: eventos gravados na mesma transação da mutação de estado.
// Um processo separado (a implementar em fases futuras) lê e publica para consumidores.
@Entity
@Table(name = "outbox")
public class Outbox {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(name = "tipo_evento", nullable = false, length = 100)
  private String tipoEvento;

  @Column(name = "agregado_id", nullable = false)
  private UUID agregadoId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String payload;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private Instant criadoEm;

  @Column(name = "publicado_em")
  private Instant publicadoEm;

  @Column(nullable = false)
  private int tentativas;

  // Instante a partir do qual o drenador pode tentar de novo. NOT NULL com default
  // NOW() no banco: evento nunca tentado é elegível já. Ver V14__outbox_backoff.sql.
  @Column(name = "proxima_tentativa_em", nullable = false)
  private Instant proximaTentativaEm;

  // Diagnóstico do último despacho falho. Sem isto, um evento parado em tentativas=5
  // não diz por quê e o operador só descobre reproduzindo.
  @Column(name = "ultimo_erro", columnDefinition = "TEXT")
  private String ultimoErro;

  /**
   * Corte da mensagem de erro. Um stack trace inteiro por linha inflaria a tabela sem acrescentar
   * diagnóstico: a causa está sempre nos primeiros caracteres.
   */
  private static final int LIMITE_ERRO = 1000;

  protected Outbox() {}

  public Outbox(UUID id, String tipoEvento, UUID agregadoId, String payload, Instant criadoEm) {
    this.id = id;
    this.tipoEvento = tipoEvento;
    this.agregadoId = agregadoId;
    this.payload = payload;
    this.criadoEm = criadoEm;
    this.tentativas = 0;
    // Elegível desde já — o drenador decide quando varrer, não esta linha.
    this.proximaTentativaEm = criadoEm;
  }

  /**
   * Instante recebido, e não {@code Instant.now()} interno, pelo mesmo motivo de {@code criadoEm}:
   * o chamador já tem o relógio da operação, e duas leituras de relógio na mesma unidade de
   * trabalho produzem instantes diferentes sem nenhum ganho.
   */
  public void marcarPublicado(Instant agora) {
    this.publicadoEm = agora;
    this.ultimoErro = null;
  }

  /**
   * Conta a falha e adia a próxima tentativa. Contar sem adiar faria o evento voltar no lote
   * seguinte imediatamente, e o drenador giraria em falso contra a mesma linha a cada varredura.
   */
  public void registrarFalha(String erro, Instant proximaTentativaEm) {
    this.tentativas++;
    this.proximaTentativaEm = proximaTentativaEm;
    this.ultimoErro =
        erro == null || erro.length() <= LIMITE_ERRO ? erro : erro.substring(0, LIMITE_ERRO);
  }

  public UUID getId() {
    return id;
  }

  public String getTipoEvento() {
    return tipoEvento;
  }

  public UUID getAgregadoId() {
    return agregadoId;
  }

  public String getPayload() {
    return payload;
  }

  public Instant getCriadoEm() {
    return criadoEm;
  }

  public int getTentativas() {
    return tentativas;
  }
}
