package com.omnitribo.integracoes.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Condição atual no ponto consultado.
 *
 * <p>{@code descricao} vem traduzida do servidor, e não do app, porque o código WMO é do provedor:
 * se ele mudar de provedor, a tabela de tradução muda aqui e nenhuma versão do app precisa ser
 * atualizada. {@code codigo} vai junto para quem quiser escolher um ícone.
 */
@Schema(description = "Condição meteorológica atual, para o card do mapa")
public record ClimaResponse(
    BigDecimal temperaturaC,
    BigDecimal sensacaoC,
    @Schema(description = "Código WMO da condição, para escolha de ícone") int codigo,
    String descricao,
    @Schema(description = "Instante da medição informado pelo provedor") Instant medidoEm) {}
