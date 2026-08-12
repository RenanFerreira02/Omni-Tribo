package com.omnitribo.missoes.api;

import com.omnitribo.missoes.dominio.CategoriaMissao;
import com.omnitribo.missoes.dominio.ComplexidadeMissao;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Corpo de criação de missão.
 *
 * <p>Não existem campos status, executorId nem criadorId: o status inicial é sempre RASCUNHO e o
 * criador vem do JWT. Aceitá-los aqui seria mass assignment — o cliente escolheria em que estado a
 * missão nasce e a quem ela pertence.
 *
 * <p><b>Também não existem xpRecompensa nem tokensRecompensa</b>, e a razão é a mesma: eram o
 * caminho pelo qual o criador escolhia quanto a própria missão valia. Medido antes da remoção: uma
 * missão sem peso, sem volume e sem destino foi criada com 5.000 XP e 1.000 tokens, o teto que o
 * {@code @Max} permitia. Hoje quem calcula é {@code CalculadoraDeRecompensa}, a partir de
 * categoria, complexidade, distância, peso e volume — e o valor é congelado com a versão da
 * fórmula. Enviar os campos mesmo assim é inofensivo: {@code fail-on-unknown-properties: false} os
 * descarta em silêncio, como já acontece com status e executorId.
 */
@CriacaoMissaoVerificador.MissaoCriacaoConsistente
@Schema(description = "Dados para criar uma missão. A missão nasce em RASCUNHO.")
public record CriarMissaoRequest(
    @NotNull(message = "Categoria é obrigatória") CategoriaMissao categoria,
    @NotBlank(message = "Título é obrigatório")
        @Size(min = 5, max = 120, message = "Título deve ter entre 5 e 120 caracteres")
        String titulo,
    @NotBlank(message = "Descrição é obrigatória")
        @Size(max = 2000, message = "Descrição deve ter no máximo 2000 caracteres")
        String descricao,
    /**
     * OPCIONAL, e só aceita zero ou nulo. Enviar valor positivo é <b>400</b>, não é ignorado.
     *
     * <p>Era {@code @NotNull} com {@code @DecimalMin("0.00") @DecimalMax("500.00")} — obrigava todo
     * cliente a enviar {@code "valorBrl": 0} num campo que não podia ser outra coisa, e as
     * constraints de faixa eram <b>validação inalcançável</b>: desde o ADR 0009 e a {@code
     * ck_missao_economia} da V15, nenhum valor entre 0,01 e 500 jamais chegaria a ser avaliado por
     * elas.
     *
     * <p><b>Por que não remover o campo de vez</b>, que era a saída óbvia: com {@code
     * fail-on-unknown-properties: false}, um cliente que enviasse {@code "valorBrl": 50} teria a
     * missão criada em silêncio, acreditando que ela vale R$ 50. É exatamente o que a regra (3)
     * deste mesmo formulário recusa fazer com {@code complexidade} — "ignorar em silêncio faria o
     * app acreditar que declarou algo que não teve efeito". Opcional + recusa explícita tira a
     * obrigação sem trocar um 400 honesto por um mal-entendido.
     */
    @DecimalMax(value = "0.00", message = "Missão não remunera em BRL — a recompensa é XP e tokens")
        @DecimalMin(value = "0.00", message = "Valor em BRL não pode ser negativo")
        BigDecimal valorBrl,
    @Schema(
            description =
                "Esforço da missão. Informe APENAS quando não houver peso e volume — com os dois"
                    + " presentes o servidor deriva, e um valor aqui é recusado com 400.")
        ComplexidadeMissao complexidade,
    @NotNull(message = "Latitude de origem é obrigatória")
        @DecimalMin(value = "-90.0", message = "Latitude deve estar entre -90 e 90")
        @DecimalMax(value = "90.0", message = "Latitude deve estar entre -90 e 90")
        BigDecimal origemLat,
    @NotNull(message = "Longitude de origem é obrigatória")
        @DecimalMin(value = "-180.0", message = "Longitude deve estar entre -180 e 180")
        @DecimalMax(value = "180.0", message = "Longitude deve estar entre -180 e 180")
        BigDecimal origemLon,
    @DecimalMin(value = "-90.0", message = "Latitude deve estar entre -90 e 90")
        @DecimalMax(value = "90.0", message = "Latitude deve estar entre -90 e 90")
        BigDecimal destinoLat,
    @DecimalMin(value = "-180.0", message = "Longitude deve estar entre -180 e 180")
        @DecimalMax(value = "180.0", message = "Longitude deve estar entre -180 e 180")
        BigDecimal destinoLon,
    @NotBlank(message = "CEP é obrigatório")
        @Pattern(regexp = "\\d{8}", message = "CEP deve ter 8 dígitos, sem hífen")
        String cep,
    @NotBlank(message = "Logradouro é obrigatório") @Size(max = 200) String logradouro,
    @NotBlank(message = "Bairro é obrigatório") @Size(max = 100) String bairro,
    @NotBlank(message = "Cidade é obrigatória") @Size(max = 100) String cidade,
    @NotBlank(message = "UF é obrigatória")
        @Pattern(regexp = "[A-Z]{2}", message = "UF deve ter 2 letras maiúsculas")
        String uf,
    @Min(value = 10, message = "Raio de check-in mínimo é 10 metros")
        @Max(value = 2000, message = "Raio de check-in máximo é 2000 metros")
        int raioCheckinM,
    @DecimalMin(value = "0.00", message = "Peso não pode ser negativo")
        @Digits(integer = 4, fraction = 2, message = "Peso deve ter no máximo 2 decimais")
        BigDecimal pesoKg,
    @DecimalMin(value = "0.00", message = "Volume não pode ser negativo")
        @Digits(integer = 6, fraction = 2, message = "Volume deve ter no máximo 2 decimais")
        BigDecimal volumeL,
    @NotNull(message = "Início da janela é obrigatório") Instant janelaInicio,
    @NotNull(message = "Fim da janela é obrigatório") Instant janelaFim,
    UUID pontoCustodiaId) {}
