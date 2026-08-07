package com.omnitribo.carteira.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "carteira")
public class Carteira {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  // usuarioId: FK para usuario — carteira→identidade é permitido
  @Column(name = "usuario_id", nullable = false, updatable = false, unique = true)
  private UUID usuarioId;

  // numeric(12,2) → BigDecimal; nunca double (erros de ponto flutuante em BRL)
  @Column(name = "saldo_brl", nullable = false, precision = 12, scale = 2)
  private BigDecimal saldoBrl;

  @Column(name = "saldo_tokens", nullable = false)
  private long saldoTokens;

  @Version
  @Column(nullable = false)
  private int versao;

  protected Carteira() {}

  public Carteira(UUID id, UUID usuarioId) {
    this.id = id;
    this.usuarioId = usuarioId;
    this.saldoBrl = BigDecimal.ZERO;
    this.saldoTokens = 0L;
  }

  public void creditar(BigDecimal valorBrl, long valorTokens) {
    this.saldoBrl = this.saldoBrl.add(valorBrl);
    this.saldoTokens += valorTokens;
  }

  /**
   * Débito com guarda de saldo.
   *
   * <p>A guarda NÃO é a recusa que o usuário vê. Quem chama já comparou o saldo sob {@code SELECT
   * ... FOR UPDATE} e respondeu 422 antes de escrever qualquer coisa — é lá que mora a regra de
   * negócio, porque só lá dá para recusar sem efeito colateral. Esta exceção é a segunda de três
   * camadas (serviço → entidade → {@code ck_carteira_saldo_nao_negativo} no banco) e significa que
   * o invariante "todo débito é precedido de verificação sob lock" deixou de valer: um 500 alto é
   * melhor que um saldo negativo silencioso num ledger financeiro.
   */
  public void debitar(BigDecimal valorBrl, long valorTokens) {
    BigDecimal novoBrl = this.saldoBrl.subtract(valorBrl);
    long novoTokens = this.saldoTokens - valorTokens;
    if (novoBrl.signum() < 0 || novoTokens < 0) {
      throw new IllegalStateException("Débito excede o saldo da carteira " + this.id + ".");
    }
    this.saldoBrl = novoBrl;
    this.saldoTokens = novoTokens;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUsuarioId() {
    return usuarioId;
  }

  public BigDecimal getSaldoBrl() {
    return saldoBrl;
  }

  public long getSaldoTokens() {
    return saldoTokens;
  }

  public int getVersao() {
    return versao;
  }
}
