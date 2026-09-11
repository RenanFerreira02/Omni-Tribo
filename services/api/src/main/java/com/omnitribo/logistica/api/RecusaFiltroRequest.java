package com.omnitribo.logistica.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Recorte do painel de recusas. Mesmo molde de {@code OutboxFiltroRequest}: os padrões moram no
 * próprio record e o controller monta o {@code PageRequest}.
 *
 * <p>A janela é em HORAS e não um par de instantes. Um intervalo aberto convidaria a consultar o
 * histórico inteiro, e o índice {@code idx_entrega_falida_recusa} (V29) serve uma faixa recente —
 * sem limite superior de janela, a consulta degrada para varredura do índice inteiro à medida que a
 * operação envelhece. O teto de 720 h (30 dias) é a maior janela em que "este ponto vive cheio"
 * ainda descreve o presente.
 */
public record RecusaFiltroRequest(
    @Min(1) @Max(720) Integer horas, @Min(0) Integer pagina, @Min(1) @Max(100) Integer tamanho) {

  /** 24 h: o ciclo da operação de entrega, e a janela em que uma rajada ainda é acionável. */
  public int horasOuPadrao() {
    return horas == null ? 24 : horas;
  }

  public int paginaOuPadrao() {
    return pagina == null ? 0 : pagina;
  }

  public int tamanhoOuPadrao() {
    return tamanho == null ? 20 : tamanho;
  }
}
