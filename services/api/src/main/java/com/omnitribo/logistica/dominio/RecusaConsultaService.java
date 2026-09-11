package com.omnitribo.logistica.dominio;

import com.omnitribo.logistica.api.RecusaPorPontoResponse;
import com.omnitribo.logistica.infra.EntregaFalidaRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leitura da frequência de recusa por ponto de custódia.
 *
 * <p>Existe porque a deduplicação do alerta operacional (ADR 0033) tirou o número de recusas de
 * {@code alerta}, onde ele estava como uma linha por evento e sem nenhum leitor. O número não foi
 * embora — {@code entrega_falida} sempre gravou toda recusa, com ponto, instante e motivo —, mas
 * passar a depender dele exige que alguém consiga lê-lo, e é essa a lição que o ADR 0032 deixou:
 * instrumento sem consumidor faz a lacuna parecer coberta.
 *
 * <p>Sem estado, sem cache e sem tabela de agregação. A consulta agrega na hora (ADR 0029).
 */
@Service
public class RecusaConsultaService {

  private final EntregaFalidaRepository entregaFalidaRepository;

  public RecusaConsultaService(EntregaFalidaRepository entregaFalidaRepository) {
    this.entregaFalidaRepository = entregaFalidaRepository;
  }

  /**
   * Recusas agrupadas por ponto e motivo nas últimas {@code horas}, da mais frequente para a menos.
   *
   * <p>{@code readOnly = true}: é leitura pura, e a marca evita que o Hibernate faça dirty checking
   * sobre nada.
   */
  @Transactional(readOnly = true)
  public Page<RecusaPorPontoResponse> listar(int horas, Pageable paginacao) {
    Instant desde = Instant.now().minus(Duration.ofHours(horas));

    return entregaFalidaRepository
        .agruparRecusas(desde, paginacao)
        .map(
            p ->
                new RecusaPorPontoResponse(
                    p.getPontoCustodiaId(),
                    p.getCodigo(),
                    p.getApelido(),
                    p.getCapacidade(),
                    p.getMotivo(),
                    p.getRecusas(),
                    p.getPrimeiraRecusa(),
                    p.getUltimaRecusa()));
  }
}
