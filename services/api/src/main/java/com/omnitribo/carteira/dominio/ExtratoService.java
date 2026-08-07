package com.omnitribo.carteira.dominio;

import com.omnitribo.carteira.api.CarteiraResponse;
import com.omnitribo.carteira.api.LancamentoFiltroRequest;
import com.omnitribo.carteira.api.LancamentoResponse;
import com.omnitribo.carteira.infra.CarteiraRepository;
import com.omnitribo.carteira.infra.LancamentoRepository;
import com.omnitribo.compartilhado.api.PaginaResponse;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Leitura da carteira e do extrato do usuário autenticado. */
@Service
public class ExtratoService {

  private static final String CARTEIRA_AUSENTE = "Carteira não encontrada.";

  private final CarteiraRepository carteiraRepository;
  private final LancamentoRepository lancamentoRepository;

  public ExtratoService(
      CarteiraRepository carteiraRepository, LancamentoRepository lancamentoRepository) {
    this.carteiraRepository = carteiraRepository;
    this.lancamentoRepository = lancamentoRepository;
  }

  @Transactional(readOnly = true)
  public CarteiraResponse saldo(UUID usuarioId) {
    return carteiraRepository
        .findByUsuarioId(usuarioId)
        .map(CarteiraResponse::de)
        .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));
  }

  /**
   * Extrato paginado, sempre cronológico decrescente.
   *
   * <p>A carteira é resolvida a partir do usuário do JWT, nunca de um id vindo do cliente: sem
   * isso, trocar um parâmetro leria o extrato alheio — vazamento de dado financeiro por IDOR.
   *
   * <p>Sob READ COMMITTED, um lançamento inserido entre o {@code count} e o {@code select} do
   * {@code Page} desloca a paginação. Aceitável e documentado para uma lista de UI; corrigir
   * exigiria snapshot consistente entre as duas consultas, ao custo de contenção num caminho de
   * leitura.
   */
  @Transactional(readOnly = true)
  public PaginaResponse<LancamentoResponse> extrato(
      UUID usuarioId, LancamentoFiltroRequest filtro) {

    UUID carteiraId =
        carteiraRepository
            .buscarIdPorUsuario(usuarioId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));

    PageRequest pagina =
        PageRequest.of(filtro.pagina(), filtro.tamanho(), Sort.by(Sort.Direction.DESC, "criadoEm"));

    Page<LancamentoResponse> page =
        lancamentoRepository.findByCarteiraId(carteiraId, pagina).map(LancamentoResponse::de);

    return PaginaResponse.de(page);
  }
}
