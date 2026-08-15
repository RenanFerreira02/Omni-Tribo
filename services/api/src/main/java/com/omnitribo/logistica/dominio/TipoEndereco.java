package com.omnitribo.logistica.dominio;

/**
 * Natureza do endereço de destino, como a transportadora a classifica.
 *
 * <p>Quatro categorias e não uma escala, pela mesma razão de {@code ComplexidadeMissao}: "quão
 * comercial é este endereço numa escala de 1 a 10" não tem resposta defensável. {@code RESIDENCIAL}
 * é a categoria de REFERÊNCIA do modelo — não tem coeficiente próprio, e o efeito dela está
 * embutido no intercepto. Ver {@link CaracteristicaRisco}.
 */
public enum TipoEndereco {
  RESIDENCIAL,
  COMERCIAL,
  CONDOMINIO,
  RURAL
}
