package com.omnitribo.logistica.dominio;

import com.omnitribo.compartilhado.api.RecursoAuditavel;
import com.omnitribo.compartilhado.dominio.Auditavel;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.logistica.infra.EntregaFalidaRepository;
import com.omnitribo.missoes.api.ConfirmacaoRetirada;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A transportadora confirma que a encomenda chegou (ADR 0026): o criador da missão de retirada é o
 * usuário-sistema, e nenhum humano conseguia confirmar. A varredura de prazo continua como rede de
 * segurança.
 *
 * <p>Bean SEPARADO de {@link EntregaFalidaService}: junto, fecharia o ciclo de beans {@code
 * EntregaFalidaService → MissaoService → EntregaFalidaService}.
 */
@Service
public class ConfirmacaoRetiradaService {

  private static final Logger log = LoggerFactory.getLogger(ConfirmacaoRetiradaService.class);

  private final EntregaFalidaRepository entregaFalidaRepository;

  /** Pela INTERFACE: nomear MissaoService faria logistica alcançar missoes.dominio (ArchUnit). */
  private final ConfirmacaoRetirada confirmacaoRetirada;

  public ConfirmacaoRetiradaService(
      EntregaFalidaRepository entregaFalidaRepository, ConfirmacaoRetirada confirmacaoRetirada) {
    this.entregaFalidaRepository = entregaFalidaRepository;
    this.confirmacaoRetirada = confirmacaoRetirada;
  }

  /**
   * Resposta à transportadora.
   *
   * @param tokensCreditados o que o executor recebeu NESTA chamada — zero num replay.
   */
  public record Resultado(
      UUID entregaFalidaId, UUID missaoId, long tokensCreditados, boolean replay)
      implements RecursoAuditavel {

    /**
     * A entrega falida é o recurso auditado: reporte e confirmação sob o mesmo {@code entidade_id}.
     */
    @Override
    public UUID idAuditoria() {
      return entregaFalidaId;
    }
  }

  /**
   * Confirma a retirada de {@code (transportadora, codigoRastreio)}. Idempotente em duas camadas:
   * {@code convertida_em} preenchido evita a chamada no retry comum; para duas confirmações
   * simultâneas, a sondagem sob FOR UPDATE em {@code concluirComCredito} separa.
   *
   * <p>Rastreio desconhecido ou entrega sem missão é 404: aqui não há fato novo a gravar.
   */
  @Auditavel(acao = "RETIRADA_CONFIRMADA", entidade = "entrega_falida")
  @Transactional
  public Resultado confirmar(String transportadora, String codigoRastreio) {
    EntregaFalida entrega =
        entregaFalidaRepository
            .findByTransportadoraAndCodigoRastreio(transportadora, codigoRastreio)
            .orElseThrow(
                () ->
                    new RecursoNaoEncontradoException(
                        "Nenhuma entrega registrada para este código de rastreio."));

    if (entrega.getMissaoId() == null) {
      // Recusada: o fato já está gravado e não há missão. Reenviar não muda nada.
      throw new RecursoNaoEncontradoException(
          "Não há missão de retirada para este código de rastreio: a encomenda não foi convertida.");
    }

    if (entrega.saiuDaCustodia()) {
      log.debug("Confirmação repetida da entrega {} — no-op.", entrega.getId());
      return new Resultado(entrega.getId(), entrega.getMissaoId(), 0L, true);
    }

    long tokens = confirmacaoRetirada.confirmarRetirada(entrega.getMissaoId());
    return new Resultado(entrega.getId(), entrega.getMissaoId(), tokens, false);
  }
}
