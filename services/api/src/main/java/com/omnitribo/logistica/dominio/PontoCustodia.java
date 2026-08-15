package com.omnitribo.logistica.dominio;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "ponto_custodia")
public class PontoCustodia {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(nullable = false, unique = true, length = 20)
  private String codigo;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private TipoPontoCustodia tipo;

  @Column(nullable = false, length = 100)
  private String apelido;

  @Column(nullable = false, columnDefinition = "geography(POINT,4326)")
  private Point ponto;

  // triboId: FK para tribo — logistica→identidade é permitido
  @Column(name = "tribo_id")
  private UUID triboId;

  @Column(nullable = false)
  private int capacidade;

  @Column(nullable = false)
  private int ocupacao;

  @Column(nullable = false)
  private boolean ativo;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private Instant criadoEm;

  protected PontoCustodia() {}

  public UUID getId() {
    return id;
  }

  public String getCodigo() {
    return codigo;
  }

  public TipoPontoCustodia getTipo() {
    return tipo;
  }

  public String getApelido() {
    return apelido;
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

  public int getCapacidade() {
    return capacidade;
  }

  public int getOcupacao() {
    return ocupacao;
  }

  public boolean isAtivo() {
    return ativo;
  }

  public Instant getCriadoEm() {
    return criadoEm;
  }

  /** Há espaço físico para mais uma encomenda? */
  public boolean temVaga() {
    return ocupacao < capacidade;
  }

  /**
   * Registra a entrada de uma encomenda na custódia.
   *
   * <p>Não é setter público por decisão: ocupação não é dado que alguém informa, é consequência de
   * uma encomenda ter chegado ou saído. O javadoc de {@code PontoCustodiaService} guardou o lugar
   * desta chamada — "quando esse fluxo chegar, ele NÃO deve virar endpoint" —, e continua valendo:
   * quem chama é {@code EntregaFalidaService} (webhook) e a conclusão de missão, nunca uma
   * requisição de usuário.
   *
   * <p>A verificação de vaga acontece no serviço, sob o {@code SELECT ... FOR UPDATE} do ponto.
   * Aqui ela é só a rede de segurança contra um chamador futuro que esqueça o lock: sem o lock,
   * dois webhooks concorrentes leem a mesma ocupação e ambos incrementam, e o ponto passa da
   * capacidade sem que nada acuse.
   */
  public void registrarEntrada() {
    if (!temVaga()) {
      throw new IllegalStateException(
          "Ponto de custódia " + codigo + " sem vaga: " + ocupacao + "/" + capacidade);
    }
    ocupacao++;
  }

  /**
   * Registra a saída de uma encomenda — a missão de retirada concluiu e o pacote foi entregue.
   *
   * <p>Piso em zero, e não é paranoia decorativa: a entrega da outbox é at-least-once, e um
   * decremento redespachado levaria a ocupação a negativo. Negativo é pior do que parece, porque
   * `temVaga()` continuaria verdadeiro e o erro só apareceria como uma capacidade que cresce
   * sozinha. A baixa é síncrona hoje justamente para não depender disto.
   */
  public void registrarSaida() {
    if (ocupacao > 0) {
      ocupacao--;
    }
  }
}
