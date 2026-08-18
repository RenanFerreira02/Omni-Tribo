package com.omnitribo.logistica.api;

import com.omnitribo.logistica.dominio.TipoEndereco;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.DayOfWeek;

/**
 * Contexto de uma tentativa de entrega, para estimar o risco de ela falhar.
 *
 * <p>Todas as faixas são validadas aqui, na borda: entrada fora de faixa vira 400 com o campo
 * apontado, pelo caminho que o {@code GlobalExceptionHandler} já trata. O domínio revalida em
 * {@code FeaturesEntrega} — redundância deliberada, porque o webhook também constrói aquele record
 * e não passa por este DTO.
 *
 * <p>{@code chuvaMm} e {@code temperaturaC} são OPCIONAIS: quando ausentes, o servidor imputa a
 * média do treino e informa isso em {@code featuresImputadas} da resposta.
 */
@Schema(description = "Contexto de uma tentativa de entrega para previsão de risco de falha")
public record PrevisaoFalhaRequest(
    @NotNull(message = "Hora da janela de entrega é obrigatória")
        @Min(value = 0, message = "Hora deve estar entre 0 e 23")
        @Max(value = 23, message = "Hora deve estar entre 0 e 23")
        @Schema(description = "Hora de início da janela de entrega, em 0–23", example = "19")
        Integer janelaHoraInicio,
    @NotNull(message = "Dia da semana é obrigatório")
        @Schema(description = "Dia da semana da tentativa", example = "SATURDAY")
        DayOfWeek diaSemana,
    @NotNull(message = "Tipo de endereço é obrigatório")
        @Schema(description = "Natureza do endereço de destino", example = "COMERCIAL")
        TipoEndereco tipoEndereco,
    @NotNull(message = "CEP é obrigatório")
        @Pattern(regexp = "\\d{8}", message = "CEP deve ter 8 dígitos, sem hífen")
        @Schema(
            description = "CEP do destino; os 3 primeiros dígitos resolvem a faixa histórica",
            example = "03170010")
        String cep,
    @NotNull(message = "Peso é obrigatório")
        @DecimalMin(value = "0.01", message = "Peso deve ser positivo")
        @DecimalMax(
            value = "1000.00",
            message = "Peso acima de 1000 kg não é entrega de última milha")
        @Schema(example = "12.50")
        BigDecimal pesoKg,
    @NotNull(message = "Volume é obrigatório")
        @DecimalMin(value = "0.01", message = "Volume deve ser positivo")
        @DecimalMax(
            value = "10000.00",
            message = "Volume acima de 10000 L não é entrega de última milha")
        @Schema(example = "60.00")
        BigDecimal volumeL,
    @NotNull(message = "Número de tentativas anteriores é obrigatório")
        @Min(value = 0, message = "Tentativas anteriores não pode ser negativo")
        @Max(value = 20, message = "Mais de 20 tentativas indica erro de integração")
        @Schema(description = "Quantas vezes esta entrega já foi tentada", example = "2")
        Integer tentativasAnteriores,
    @DecimalMin(value = "0.0", message = "Chuva não pode ser negativa")
        @DecimalMax(value = "500.0", message = "Chuva acima de 500 mm não é meteorologia, é erro")
        @Schema(
            description = "Chuva prevista na janela, em mm. Ausente ⇒ imputado",
            example = "8.0")
        BigDecimal chuvaMm,
    @DecimalMin(value = "-50.0", message = "Temperatura abaixo de -50 °C não ocorre na operação")
        @DecimalMax(value = "60.0", message = "Temperatura acima de 60 °C não ocorre na operação")
        @Schema(description = "Temperatura prevista, em °C. Ausente ⇒ imputado", example = "19.0")
        BigDecimal temperaturaC) {}
