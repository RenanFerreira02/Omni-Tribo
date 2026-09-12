package com.omnitribo.compartilhado.api;

import com.omnitribo.compartilhado.dominio.Outbox;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Um evento que esgotou as tentativas de despacho e nunca foi entregue — a carta-morta da outbox.
 *
 * <p><b>O {@code payload} do evento NÃO está aqui, e é decisão de segurança, não esquecimento.</b>
 * {@code EntregaFalidaConvertida} carrega {@code lat}/{@code lon} de endereço residencial de
 * destinatário e {@code MissaoConcluida} carrega {@code executorId} e o valor creditado. Uma
 * listagem HTTP paginada é o pior lugar para esse dado: fica em log de acesso, em histórico e em
 * qualquer proxy no caminho — a mesma razão pela qual {@code POST /logistica/previsao-falha} é POST
 * sem escrita. Esta resposta é um índice de diagnóstico (o quê falhou, quando, e por quê); quem
 * precisa do corpo tem o {@code id}. Ver ADR 0031.
 */
@Schema(description = "Evento da outbox que esgotou as tentativas e nunca foi entregue")
public record EventoEsgotadoResponse(
    UUID id,
    String tipoEvento,
    UUID agregadoId,
    Instant criadoEm,
    int tentativas,
    @Schema(
            description =
                "Instante da última tentativa somado ao backoff. Já passou, por definição.")
        Instant proximaTentativaEm,
    @Schema(description = "Causa da última falha de despacho, truncada em 1000 caracteres")
        String ultimoErro) {

  public static EventoEsgotadoResponse de(Outbox evento) {
    return new EventoEsgotadoResponse(
        evento.getId(),
        evento.getTipoEvento(),
        evento.getAgregadoId(),
        evento.getCriadoEm(),
        evento.getTentativas(),
        evento.getProximaTentativaEm(),
        evento.getUltimoErro());
  }
}
