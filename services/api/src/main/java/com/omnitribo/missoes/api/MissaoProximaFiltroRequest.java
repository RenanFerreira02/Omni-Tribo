package com.omnitribo.missoes.api;

import com.omnitribo.missoes.dominio.CategoriaMissao;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Filtros da busca por proximidade.
 *
 * <p>Não há campo de status: a busca devolve SEMPRE e SOMENTE missões ABERTA. Isso não é uma
 * limitação a remover depois sem pensar — é o que torna o resultado independente de quem pergunta,
 * e é essa propriedade que autoriza a chave de cache a não conter o id do usuário (ver
 * ChaveProximidade). Aceitar um status vindo do cliente permitiria pedir RASCUNHO e transformaria a
 * resposta em algo específico do solicitante.
 *
 * <p>Boxed em vez de primitivo em raioMetros e limite pelo mesmo motivo já documentado em
 * MissaoFiltroRequest para pagina/tamanho: parâmetro de query ausente não binda em tipo primitivo.
 * Os defaults ficam no construtor compacto.
 */
@Schema(description = "Parâmetros da busca de missões próximas")
public record MissaoProximaFiltroRequest(
    @NotNull(message = "Latitude é obrigatória")
        @DecimalMin(value = "-90.0", message = "Latitude deve estar entre -90 e 90")
        @DecimalMax(value = "90.0", message = "Latitude deve estar entre -90 e 90")
        BigDecimal lat,
    @NotNull(message = "Longitude é obrigatória")
        @DecimalMin(value = "-180.0", message = "Longitude deve estar entre -180 e 180")
        @DecimalMax(value = "180.0", message = "Longitude deve estar entre -180 e 180")
        BigDecimal lon,
    @Schema(description = "Raio de busca em metros. Default 2000, máximo 20000.")
        @Min(value = 1, message = "Raio deve ser positivo")
        @Max(value = 20000, message = "Raio máximo é 20000 metros")
        Integer raioMetros,
    @Schema(description = "Filtro opcional por categoria. Nulo traz todas.")
        CategoriaMissao categoria,
    @Schema(description = "Máximo de resultados. Default 50, máximo 100.")
        @Min(value = 1, message = "Limite deve ser positivo")
        @Max(value = 100, message = "Limite máximo é 100")
        Integer limite) {

  public static final int RAIO_PADRAO_M = 2000;
  public static final int LIMITE_PADRAO = 50;

  public MissaoProximaFiltroRequest {
    if (raioMetros == null) {
      raioMetros = RAIO_PADRAO_M;
    }
    if (limite == null) {
      limite = LIMITE_PADRAO;
    }
  }
}
