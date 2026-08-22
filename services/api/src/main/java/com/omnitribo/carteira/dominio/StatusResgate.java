package com.omnitribo.carteira.dominio;

/**
 * Ciclo de vida de um resgate. Espelhado num {@code CHECK} da V25.
 *
 * <p>Duas posições e UMA transição: {@code PENDENTE → UTILIZADO}, feita pelo parceiro via endpoint
 * ADMIN. <b>Não há caminho de volta</b>, e a ausência é decisão econômica, não esquecimento:
 * reverter um resgate significaria RESSUSCITAR token já queimado, isto é, emitir moeda fora do
 * aporte do patrocinador — exatamente o que o ADR 0024 concentrou num ponto único e auditado. Se um
 * dia for preciso desfazer um resgate, isso merece ADR próprio e um motivo de lançamento próprio.
 */
public enum StatusResgate {
  /** Resgatado e pago: o token já foi queimado, e a pessoa tem um código para apresentar. */
  PENDENTE,

  /** O parceiro entregou o benefício e deu baixa. Terminal. */
  UTILIZADO
}
