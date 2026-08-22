package com.omnitribo.carteira.dominio;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.locationtech.jts.geom.Point;

/**
 * Comércio do bairro que aceita token em troca de benefício.
 *
 * <p>Vive em {@code carteira} e não num módulo novo: os oito módulos são fixos, e criar o nono é
 * decisão de ADR (precedente: {@code integracoes}, ADR 0011). {@code carteira} é dona das operações
 * de valor e já abriga {@code SaqueService}, a saída em BRL desligada — o resgate é a saída em
 * TOKEN, e o ledger fica junto do seu sumidouro.
 *
 * <p><b>Distância não é campo.</b> Ela é derivada por {@code ST_Distance} sobre {@code geography} a
 * cada consulta, dentro de {@code ConsultasGeoespaciais}. Uma coluna de distância seria a resposta
 * de uma pergunta só — a de quem estava num lugar específico quando ela foi calculada.
 */
@Entity
@Table(name = "parceiro")
public class Parceiro {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(nullable = false, length = 100)
  private String nome;

  @Column(nullable = false, columnDefinition = "geography(POINT,4326)")
  private Point ponto;

  // FK para tribo — carteira→identidade é permitido. Nulável: parceiro pode atender mais de um
  // bairro, e nesse caso só a proximidade o encontra.
  @Column(name = "tribo_id")
  private UUID triboId;

  @Column(nullable = false, length = 8)
  private String cep;

  @Column(nullable = false, length = 200)
  private String logradouro;

  @Column(nullable = false, length = 100)
  private String bairro;

  @Column(nullable = false, length = 100)
  private String cidade;

  @Column(nullable = false, length = 2)
  private String uf;

  @Column(nullable = false)
  private boolean ativo;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private Instant criadoEm;

  protected Parceiro() {}

  public UUID getId() {
    return id;
  }

  public String getNome() {
    return nome;
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Point de JTS é imutável após construção; cópia defensiva sem benefício")
  public Point getPonto() {
    return ponto;
  }

  public UUID getTriboId() {
    return triboId;
  }

  public String getCep() {
    return cep;
  }

  public String getLogradouro() {
    return logradouro;
  }

  public String getBairro() {
    return bairro;
  }

  public String getCidade() {
    return cidade;
  }

  public String getUf() {
    return uf;
  }

  public boolean isAtivo() {
    return ativo;
  }

  public Instant getCriadoEm() {
    return criadoEm;
  }
}
