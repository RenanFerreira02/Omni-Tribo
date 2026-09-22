package com.omnitribo.missoes.api;

import com.omnitribo.missoes.dominio.ComplexidadeMissao;
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
 * e criadorId. O VALOR da recompensa nunca vem do cliente (ADR 0009) e a categoria é imutável após
 * a criação. Status e executor só mudam pela máquina de estados. Enviá-los no JSON não causa erro:
 * são simplesmente ignorados, porque o record não os declara.
 *
 * <p><b>{@code recompensaEmToken} é a exceção, e ela não contradiz o acima:</b> o cliente não
 * escolhe QUANTO a missão paga, escolhe SE ela paga em moeda. O quanto continua sendo derivado pelo
 * servidor, e em RASCUNHO ele é recalculado a cada edição (ADR 0036) — o argumento que mantinha a
 * recompensa fora do PATCH ("mudaria o contrato sob os pés de quem está prestes a aceitar") só vale
 * a partir de ABERTA, onde a missão já está visível.
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
        BigDecimal volumeL,
    /**
     * Trocar entre recompensar em token e recompensar só em XP. Nulo = não alterar.
     *
     * <p>É a edição que destrava quem criou uma missão comunitária e não conseguiu financiar o pote
     * — o caso que motivou o ADR 0035. Só vale em RASCUNHO e só em TRIBO e AJUDA; fora disso o
     * serviço recusa, com 409 e 422 respectivamente, porque as duas condições dependem da missão e
     * não do corpo.
     */
    @Schema(
            description =
                "Troca entre recompensa em token e só XP. Só em RASCUNHO, só TRIBO e AJUDA."
                    + " Ausente = não alterar.")
        Boolean recompensaEmToken,
    /**
     * Esforço declarado. Só faz sentido onde não há peso e volume — TRIBO e AJUDA.
     *
     * <p>Entrou junto com o recálculo do ADR 0036: sem ela, um rascunho TRIBO ou AJUDA não teria
     * COMO mudar a própria recompensa, porque a complexidade é o único insumo que essas categorias
     * têm. Peso e volume já eram editáveis, e a assimetria não tinha razão — ela só não aparecia
     * porque nada no app chegava a editar.
     *
     * <p>Vale nos DOIS estados editáveis, como peso e volume: é insumo, e insumo segue os dados.
     * Numa missão publicada com pote comprometido a recusa vem do POTE, não do status — ver {@code
     * MissaoService.recongelarRecompensaEditada}.
     *
     * <p>Com peso E volume presentes o servidor deriva, e um valor aqui é 422 — mesma regra da
     * criação, que é 400 por ser verificável só com o corpo. Aqui depende da missão.
     */
    @Schema(
            description =
                "Esforço declarado. Recusado quando a missão tem peso e volume — ali é derivado.")
        ComplexidadeMissao complexidade) {}
