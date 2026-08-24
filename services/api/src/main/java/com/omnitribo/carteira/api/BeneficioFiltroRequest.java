package com.omnitribo.carteira.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Filtro de {@code GET /api/v1/beneficios}. Proximidade OU tribo, nunca os dois.
 *
 * <p>Não são combináveis de propósito: "perto de mim E da minha tribo" descreve dois critérios de
 * pertencimento sobre o mesmo conjunto, e o resultado seria indistinguível do mais restritivo — com
 * a desvantagem de que ninguém saberia qual dos dois recortou.
 */
public record BeneficioFiltroRequest(
    @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0") BigDecimal lat,
    @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0") BigDecimal lon,
    @Min(value = 100, message = "Raio mínimo é 100 m")
        @Max(value = 50000, message = "Raio máximo é 50 km")
        Integer raioMetros,
    UUID triboId,
    @Min(0) Integer pagina,
    @Min(1) @Max(100) Integer tamanho) {

  public boolean porProximidade() {
    return lat != null && lon != null && raioMetros != null;
  }

  public boolean porTribo() {
    return triboId != null;
  }

  /** Coordenada pela metade é erro do cliente, não um recorte parcial. */
  public boolean geoConsistente() {
    boolean nenhum = lat == null && lon == null && raioMetros == null;
    return nenhum || porProximidade();
  }

  public int paginaOuPadrao() {
    return pagina == null ? 0 : pagina;
  }

  public int tamanhoOuPadrao() {
    return tamanho == null ? 20 : tamanho;
  }
}
