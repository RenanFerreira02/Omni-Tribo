package com.omnitribo.notificacoes.dominio;

import com.omnitribo.compartilhado.api.PaginaResponse;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.notificacoes.api.AlertaFiltroRequest;
import com.omnitribo.notificacoes.api.AlertaResponse;
import com.omnitribo.notificacoes.infra.AlertaRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Leitura e marcação da caixa de entrada. */
@Service
public class AlertaService {

  private static final String NAO_ENCONTRADO = "Notificação não encontrada.";

  private final AlertaRepository alertaRepository;

  public AlertaService(AlertaRepository alertaRepository) {
    this.alertaRepository = alertaRepository;
  }

  @Transactional(readOnly = true)
  public PaginaResponse<AlertaResponse> listar(UUID usuarioId, AlertaFiltroRequest filtro) {
    Pageable pagina = PageRequest.of(filtro.paginaOuPrimeira(), filtro.tamanhoOuPadrao());

    Page<Alerta> resultado =
        filtro.apenasNaoLidosOuFalso()
            ? alertaRepository.findByUsuarioIdAndLidoFalseOrderByCriadoEmDesc(usuarioId, pagina)
            : alertaRepository.findByUsuarioIdOrderByCriadoEmDesc(usuarioId, pagina);

    return PaginaResponse.de(resultado.map(AlertaService::responseDe));
  }

  @Transactional(readOnly = true)
  public long contarNaoLidos(UUID usuarioId) {
    return alertaRepository.countByUsuarioIdAndLidoFalse(usuarioId);
  }

  /**
   * Marca como lido. IDEMPOTENTE: marcar de novo devolve o mesmo estado, sem erro.
   *
   * <p>É o comportamento certo para esta operação — o app marca ao abrir a notificação, e um retry
   * de rede ou um segundo toque não são condições excepcionais. Um 409 aqui só faria a tela mostrar
   * erro por ter feito exatamente o que devia.
   */
  @Transactional
  public AlertaResponse marcarLido(UUID alertaId, UUID usuarioId) {
    Alerta alerta =
        alertaRepository
            .findByIdAndUsuarioId(alertaId, usuarioId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(NAO_ENCONTRADO));

    alerta.marcarLido();
    return responseDe(alertaRepository.save(alerta));
  }

  private static AlertaResponse responseDe(Alerta alerta) {
    return new AlertaResponse(
        alerta.getId(),
        alerta.getTipo(),
        alerta.getTitulo(),
        alerta.getCorpo(),
        alerta.getMissaoId(),
        alerta.isLido(),
        alerta.getCriadoEm());
  }
}
