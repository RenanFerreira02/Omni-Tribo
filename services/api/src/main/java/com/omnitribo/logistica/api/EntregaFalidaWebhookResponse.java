package com.omnitribo.logistica.api;

import com.omnitribo.logistica.dominio.EntregaFalidaService;
import java.util.UUID;

/**
 * Resposta do webhook, sempre 200 quando a assinatura confere.
 *
 * <p>Recusa por lotação NÃO é erro HTTP: a requisição foi bem formada, autenticada e processada, e
 * o fato ficou gravado. Devolver 4xx faria a transportadora tratar como falha de integração e
 * reenviar em laço — e o reenvio encontraria o mesmo ponto lotado, indefinidamente. O desfecho vai
 * no corpo, que é onde ele pode ser lido sem ambiguidade.
 *
 * @param desfecho CONVERTIDA ou RECUSADA.
 * @param missaoId nulo quando recusada.
 * @param replay verdadeiro quando a chamada era repetição de uma já processada. A transportadora
 *     pode usá-lo para distinguir "criei agora" de "já estava criado", mas não precisa: o corpo é
 *     idêntico ao da primeira vez, que é o que torna o retry seguro.
 */
public record EntregaFalidaWebhookResponse(
    UUID entregaFalidaId,
    EntregaFalidaService.Desfecho desfecho,
    UUID missaoId,
    boolean replay,
    String mensagem) {

  public static EntregaFalidaWebhookResponse de(EntregaFalidaService.ResultadoRegistro resultado) {
    String mensagem =
        switch (resultado.desfecho()) {
          case CONVERTIDA ->
              "Encomenda registrada e missão de retirada publicada para a comunidade.";
          case RECUSADA ->
              "Ponto de custódia sem vaga. A ocorrência foi registrada e nenhuma missão foi criada.";
        };
    return new EntregaFalidaWebhookResponse(
        resultado.entregaFalidaId(),
        resultado.desfecho(),
        resultado.missaoId(),
        resultado.replay(),
        mensagem);
  }
}
