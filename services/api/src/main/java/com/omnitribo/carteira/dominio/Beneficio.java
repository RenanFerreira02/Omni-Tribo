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
 * O que o token compra. Item de catálogo de um {@link Parceiro}.
 *
 * <p>{@code custoTokens} é o preço VIGENTE. O resgate congela o que foi cobrado na própria linha de
 * {@link Resgate} — sem isso, um reajuste reinterpretaria retroativamente todo resgate já feito, e
 * sumiria a resposta para "quanto esta pessoa pagou por isto, no dia em que pagou?". Mesmo
 * raciocínio de {@code missao.versao_formula}.
 */
@Entity
@Table(name = "beneficio")
public class Beneficio {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(name = "parceiro_id", nullable = false, updatable = false)
  private UUID parceiroId;

  @Column(nullable = false, length = 120)
  private String titulo;

  @Column(nullable = false, length = 500)
  private String descricao;

  @Column(name = "custo_tokens", nullable = false)
  private long custoTokens;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private TipoBeneficio tipo;

  @Column(nullable = false)
  private boolean ativo;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private Instant criadoEm;

  protected Beneficio() {}

  public Beneficio(
      UUID id,
      UUID parceiroId,
      String titulo,
      String descricao,
      long custoTokens,
      TipoBeneficio tipo,
      Instant criadoEm) {
    this.id = id;
    this.parceiroId = parceiroId;
    this.titulo = titulo;
    this.descricao = descricao;
    this.custoTokens = custoTokens;
    this.tipo = tipo;
    this.ativo = true;
    this.criadoEm = criadoEm;
  }

  public UUID getId() {
    return id;
  }

  public UUID getParceiroId() {
    return parceiroId;
  }

  public String getTitulo() {
    return titulo;
  }

  public String getDescricao() {
    return descricao;
  }

  public long getCustoTokens() {
    return custoTokens;
  }

  public TipoBeneficio getTipo() {
    return tipo;
  }

  public boolean isAtivo() {
    return ativo;
  }

  public Instant getCriadoEm() {
    return criadoEm;
  }
}
