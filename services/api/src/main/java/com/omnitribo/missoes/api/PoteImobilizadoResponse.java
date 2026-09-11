package com.omnitribo.missoes.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Uma linha do diagnóstico de pote imobilizado.
 *
 * <p>Espelha {@link PoteImobilizado}, incluindo a ausência de título, criador e coordenada — ver o
 * javadoc daquele record para o motivo. O que esta camada acrescenta é {@code horasParadas},
 * derivada na resposta e não guardada em lugar nenhum: idade é relativa ao instante da consulta, e
 * materializá-la seria criar um número que envelhece dentro do próprio JSON.
 */
@Schema(description = "Missão não-terminal cujo pote está parado além do limiar de diagnóstico")
public record PoteImobilizadoResponse(
    UUID missaoId,
    String status,
    String categoria,
    @Schema(
            description =
                "COMUNIDADE ou PATROCINADOR. CUNHAGEM não aparece: missão que cunha na "
                    + "conclusão nunca forma pote, então nunca tem o que imobilizar.")
        String fontePote,
    @Schema(description = "Tokens presos neste pote") long poteTokens,
    @Schema(
            description =
                "Quanto a missão promete pagar. Menor que poteTokens é impossível para "
                    + "missão publicada; maior significa rascunho ainda subfinanciado.")
        long tokensRecompensa,
    @Schema(description = "Marco usado para medir: janela_fim em ABERTA, estado_desde nos demais")
        Instant paradaDesde,
    @Schema(description = "Idade em horas no instante da consulta, derivada de paradaDesde")
        long horasParada,
    @Schema(
            description =
                "true: existe varredura por prazo para este status e ela NÃO drenou a "
                    + "missão — o suspeito é o job. false: varredura nenhuma alcança este status, e a "
                    + "saída depende de um humano específico aparecer.")
        boolean varreduraCobre) {

  public static PoteImobilizadoResponse de(PoteImobilizado pote, Instant agora) {
    return new PoteImobilizadoResponse(
        pote.missaoId(),
        pote.status().name(),
        pote.categoria().name(),
        pote.fontePote().name(),
        pote.poteTokens(),
        pote.tokensRecompensa(),
        pote.paradaDesde(),
        Duration.between(pote.paradaDesde(), agora).toHours(),
        pote.varreduraCobre());
  }
}
