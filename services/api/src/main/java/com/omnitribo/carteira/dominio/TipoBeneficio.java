package com.omnitribo.carteira.dominio;

/**
 * Como o benefício se expressa. Espelhado num {@code CHECK} da V24.
 *
 * <p><b>Não existe um terceiro valor "VALOR" ou "REAIS", e não pode existir.</b> Preço de benefício
 * em moeda corrente publica uma cotação token→real implícita: quem lê "R$ 10 por 30 tokens" sabe
 * quanto vale um token, e a partir daí o catálogo inteiro é uma tabela de câmbio. Token conversível
 * é dinheiro, com KYC e enquadramento regulatório junto — ADR 0009 §6.
 */
public enum TipoBeneficio {
  /** Um objeto ou serviço: "um café coado", "um remendo de câmara de ar". */
  BEM,

  /** Um desconto proporcional: "20% na revisão". Proporção, nunca o valor absoluto descontado. */
  PERCENTUAL
}
