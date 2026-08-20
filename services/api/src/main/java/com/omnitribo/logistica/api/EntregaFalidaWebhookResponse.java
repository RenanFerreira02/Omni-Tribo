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
 * @param desfecho CONVERTIDA, RECUSADA ou SEM_PATROCINIO.
 * @param missaoId nulo nos dois desfechos que não criam missão.
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
          // Diz o que fazer, não o que aconteceu por dentro: a causa exata (sem patrocinador,
          // patrocínio desativado, saldo insuficiente) é estado financeiro de um terceiro e não
          // muda a ação da transportadora, que é falar com o contato comercial. Reenviar não
          // resolve, e a frase precisa deixar isso explícito — senão o retry automático vira laço.
          case SEM_PATROCINIO ->
              "Sem patrocínio ativo para esta transportadora: a ocorrência foi registrada e"
                  + " nenhuma missão foi criada. Reenviar não altera o resultado.";
        };
    return new EntregaFalidaWebhookResponse(
        resultado.entregaFalidaId(),
        resultado.desfecho(),
        resultado.missaoId(),
        resultado.replay(),
        mensagem);
  }
}
