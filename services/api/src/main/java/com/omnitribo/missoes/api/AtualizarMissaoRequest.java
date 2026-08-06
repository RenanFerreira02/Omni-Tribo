package com.omnitribo.missoes.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Corpo do PATCH. Campos nulos significam "não alterar".
 *
 * <p>Ausentes de propósito: valorBrl, tokensRecompensa, xpRecompensa, categoria, status, executorId
 * e criadorId. Recompensa e categoria são imutáveis após a criação — alterá-las com a missão já
 * ABERTA mudaria o contrato sob os pés de quem está prestes a aceitar. Status e executor só mudam
 * pela máquina de estados. Enviá-los no JSON não causa erro: são simplesmente ignorados, porque o
 * record não os declara.
 */
@EdicaoMissaoVerificador.MissaoEdicaoConsistente
@Schema(description = "Campos editáveis de uma missão em RASCUNHO ou ABERTA. Nulo = não alterar.")
public record AtualizarMissaoRequest(
    @Size(min = 5, max = 120, message = "Título deve ter entre 5 e 120 caracteres") String titulo,
    @Size(max = 2000, message = "Descrição deve ter no máximo 2000 caracteres") String descricao,
    @Pattern(regexp = "\\d{8}", message = "CEP deve ter 8 dígitos, sem hífen") String cep,
    @Size(max = 200) String logradouro,
    @Size(max = 100) String bairro,
    @Size(max = 100) String cidade,
    @Pattern(regexp = "[A-Z]{2}", message = "UF deve ter 2 letras maiúsculas") String uf,
    @DecimalMin(value = "-90.0", message = "Latitude deve estar entre -90 e 90")
        @DecimalMax(value = "90.0", message = "Latitude deve estar entre -90 e 90")
        BigDecimal origemLat,
    @DecimalMin(value = "-180.0", message = "Longitude deve estar entre -180 e 180")
        @DecimalMax(value = "180.0", message = "Longitude deve estar entre -180 e 180")
        BigDecimal origemLon,
    @DecimalMin(value = "-90.0", message = "Latitude deve estar entre -90 e 90")
        @DecimalMax(value = "90.0", message = "Latitude deve estar entre -90 e 90")
        BigDecimal destinoLat,
    @DecimalMin(value = "-180.0", message = "Longitude deve estar entre -180 e 180")
        @DecimalMax(value = "180.0", message = "Longitude deve estar entre -180 e 180")
        BigDecimal destinoLon,
    Instant janelaInicio,
    Instant janelaFim,
    @Min(value = 10, message = "Raio de check-in mínimo é 10 metros")
        @Max(value = 2000, message = "Raio de check-in máximo é 2000 metros")
        Integer raioCheckinM,
    @DecimalMin(value = "0.00", message = "Peso não pode ser negativo")
        @Digits(integer = 4, fraction = 2, message = "Peso deve ter no máximo 2 decimais")
        BigDecimal pesoKg,
    @DecimalMin(value = "0.00", message = "Volume não pode ser negativo")
        @Digits(integer = 6, fraction = 2, message = "Volume deve ter no máximo 2 decimais")
        BigDecimal volumeL) {}
