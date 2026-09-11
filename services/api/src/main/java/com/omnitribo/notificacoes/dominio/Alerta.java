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
 * <p>Tabela criada em V7. A coluna {@code usuario_id} aceita nulo no schema, e <b>dois caminhos de
 * escrita produzem isso</b>: {@code DespachanteAlertaService.gravarPontoLotado} e {@code
 * gravarSemPatrocinio}, os alertas operacionais globais. A caixa de entrada os ignora de propósito
 * — "lido" é estado POR USUÁRIO, e uma linha compartilhada não teria onde guardá-lo —, então eles
 * não aparecem em nenhuma listagem deste módulo. Quem lê o fato por trás deles é {@code GET
 * /api/v1/admin/pontos-custodia/recusas}, sobre {@code entrega_falida}.
 *
 * <p><b>A V29 acrescentou à tabela duas colunas que esta entidade NÃO mapeia</b>, {@code
 * referencia} e {@code janela_inicio}. É deliberado: elas são a chave de deduplicação do alerta
 * operacional, pertencem ao caminho de escrita e são gravadas só pelo {@code INSERT ... ON
 * CONFLICT} de {@code AlertaRepository.inserirOperacionalSeAusente}. Mapeá-las aqui poria dois
 * campos sem leitor numa entidade que modela a caixa de entrada do usuário, onde as duas são sempre
 * nulas. {@code ddl-auto: validate} não se importa com coluna não mapeada — ele exige o contrário,
 * que toda coluna mapeada exista.
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
