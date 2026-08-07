package com.omnitribo.compartilhado.dominio;

import com.omnitribo.compartilhado.infra.OutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drena a outbox: lê eventos pendentes e os entrega, com tentativas e backoff exponencial.
 *
 * <p>É a metade de leitura do padrão descrito em {@code PublicadorEventos}. A metade de escrita
 * garante que a intenção de notificar é durável junto com o fato; esta garante que a intenção vira
 * entrega, mesmo que o destino esteja fora do ar por um tempo.
 */
@Service
public class DrenadorOutboxService {

  private static final Logger log = LoggerFactory.getLogger(DrenadorOutboxService.class);

  private final OutboxRepository outboxRepository;
  private final DespachanteAlerta despachanteAlerta;
  private final int maximoTentativas;
  private final Duration backoffBase;

  public DrenadorOutboxService(
      OutboxRepository outboxRepository,
      DespachanteAlerta despachanteAlerta,
      @Value("${app.outbox.maximo-tentativas:5}") int maximoTentativas,
      @Value("${app.outbox.backoff-base:PT30S}") Duration backoffBase) {
    this.outboxRepository = outboxRepository;
    this.despachanteAlerta = despachanteAlerta;
    this.maximoTentativas = maximoTentativas;
    this.backoffBase = backoffBase;
  }

  /**
   * Processa um lote e devolve quantos eventos foram tratados (entregues ou adiados).
   *
   * <p>Transação ÚNICA por lote, e o isolamento entre eventos vem do try/catch: uma falha de
   * despacho é capturada e vira {@code registrarFalha}, não uma exceção que derrubaria o lote
   * inteiro. Se um evento envenenado pudesse abortar a transação, um único payload malformado
   * pararia a fila para todos os outros — o modo de falha mais caro que uma outbox pode ter.
   *
   * <p>O SKIP LOCKED de {@code buscarPendentesParaPublicar} garante que dois drenadores
   * concorrentes peguem lotes disjuntos em vez de disputarem as mesmas linhas.
   */
  @Transactional
  public int drenarLote(int limite) {
    Instant agora = Instant.now();
    List<Outbox> pendentes =
        outboxRepository.buscarPendentesParaPublicar(
            agora, maximoTentativas, PageRequest.of(0, limite));

    if (pendentes.isEmpty()) {
      return 0;
    }

    for (Outbox evento : pendentes) {
      try {
        despachanteAlerta.despachar(evento);
        evento.marcarPublicado(agora);
      } catch (RuntimeException e) {
        // Backoff exponencial: 30s, 1min, 2min, 4min… Sem crescimento, um destino fora do ar
        // receberia a mesma rajada a cada varredura e o retry viraria ataque contra ele.
        Duration espera = backoffBase.multipliedBy(1L << evento.getTentativas());
        evento.registrarFalha(e.toString(), agora.plus(espera));

        log.warn(
            "Falha ao despachar evento {} da outbox (tentativa {} de {}): {}",
            evento.getId(),
            evento.getTentativas(),
            maximoTentativas,
            e.toString());
      }
    }

    outboxRepository.saveAll(pendentes);
    return pendentes.size();
  }
}
