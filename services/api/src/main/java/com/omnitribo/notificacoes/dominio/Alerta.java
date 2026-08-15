package com.omnitribo.notificacoes.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Uma entrada na caixa de notificações do usuário.
 *
 * <p>Mora em {@code notificacoes} desde que o módulo deixou de ser vazio. Antes vivia em {@code
 * compartilhado}, onde escapava da regra do ArchUnit por isenção — a mudança não é cosmética: agora
 * qualquer acesso de fora a esta classe reprova o teste de arquitetura, que é o comportamento certo
 * para a entidade de um módulo de negócio.
 *
 * <p>Tabela criada em V7. A coluna {@code usuario_id} aceita nulo no schema, para um eventual
 * alerta global — mas nenhum caminho de escrita produz isso hoje, e a caixa de entrada ignora esses
 * casos de propósito: "lido" é estado POR USUÁRIO, e uma linha compartilhada não teria onde
 * guardá-lo.
 */
@Entity
@Table(name = "alerta")
public class Alerta {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(name = "usuario_id")
  private UUID usuarioId;

  @Column(nullable = false, length = 50)
  private String tipo;

  @Column(nullable = false, length = 200)
  private String titulo;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String corpo;

  // UUID puro, sem FK — fronteira notificacoes→missoes; ver V7__compartilhado.sql
  @Column(name = "missao_id")
  private UUID missaoId;

  @Column(nullable = false)
  private boolean lido;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private Instant criadoEm;

  /**
   * 0 normal, 1 risco MEDIO, 2 risco ALTO.
   *
   * <p>{@code short} e não enum com {@code varchar}, ao contrário do resto do projeto: aqui o valor
   * é ORDENÁVEL e a ordenação é o uso principal da coluna. Em ordem alfabética {@code 'ALTO' <
   * 'BAIXO' < 'MEDIO'}, que é exatamente o contrário do pretendido — um {@code ORDER BY} sobre
   * texto colocaria o alerta mais urgente no fim da lista.
   */
  @Column(nullable = false)
  private short prioridade;

  protected Alerta() {}

  /** Alerta de prioridade normal — a maioria. */
  public Alerta(
      UUID id,
      UUID usuarioId,
      String tipo,
      String titulo,
      String corpo,
      UUID missaoId,
      Instant criadoEm) {
    this(id, usuarioId, tipo, titulo, corpo, missaoId, criadoEm, PRIORIDADE_NORMAL);
  }

  public Alerta(
      UUID id,
      UUID usuarioId,
      String tipo,
      String titulo,
      String corpo,
      UUID missaoId,
      Instant criadoEm,
      short prioridade) {
    this.id = id;
    this.usuarioId = usuarioId;
    this.tipo = tipo;
    this.titulo = titulo;
    this.corpo = corpo;
    this.missaoId = missaoId;
    this.lido = false;
    this.criadoEm = criadoEm;
    this.prioridade = prioridade;
  }

  public static final short PRIORIDADE_NORMAL = 0;
  public static final short PRIORIDADE_MEDIA = 1;
  public static final short PRIORIDADE_ALTA = 2;

  public short getPrioridade() {
    return prioridade;
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
