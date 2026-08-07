package com.omnitribo.carteira.api;

import com.omnitribo.carteira.dominio.Lancamento;
import com.omnitribo.carteira.dominio.MotivoLancamento;
import com.omnitribo.carteira.dominio.SinalLancamento;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Uma linha do extrato.
 *
 * <p>NÃO expõe {@code chaveIdempotencia}, e isso é deliberado. A chave gravada é um sha256 do
 * material que inclui a chave que o cliente enviou; devolvê-la no extrato permitiria a qualquer
 * pessoa com acesso à resposta enumerar ou confirmar chaves alheias por comparação, e ela não tem
 * uso nenhum para quem lê um extrato. O identificador público da operação é o {@code id} do
 * lançamento.
 */
@Schema(description = "Lançamento do ledger — imutável após o registro")
public record LancamentoResponse(
    UUID id,
    SinalLancamento sinal,
    MotivoLancamento motivo,
    BigDecimal valorBrl,
    long valorTokens,
    UUID missaoId,
    @Schema(description = "Carteira da outra ponta, em transferências") UUID contraparteCarteiraId,
    String mensagem,
    @Schema(description = "Saldo de BRL resultante deste lançamento") BigDecimal saldoAposBrl,
    @Schema(description = "Saldo de tokens resultante deste lançamento") long saldoAposTokens,
    Instant criadoEm) {

  public static LancamentoResponse de(Lancamento l) {
    return new LancamentoResponse(
        l.getId(),
        l.getSinal(),
        l.getMotivo(),
        l.getValorBrl(),
        l.getValorTokens(),
        l.getMissaoId(),
        l.getContraparteCarteiraId(),
        l.getMensagem(),
        l.getSaldoAposBrl(),
        l.getSaldoAposTokens(),
        l.getCriadoEm());
  }
}
