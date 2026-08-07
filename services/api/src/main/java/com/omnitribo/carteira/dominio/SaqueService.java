package com.omnitribo.carteira.dominio;

import com.omnitribo.carteira.api.SaqueResponse;
import com.omnitribo.carteira.infra.CarteiraRepository;
import com.omnitribo.compartilhado.dominio.Auditavel;
import com.omnitribo.compartilhado.dominio.ChaveIdempotencia;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.compartilhado.dominio.RegraNegocioVioladaException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saque de BRL da carteira.
 *
 * <p>Escopo desta fase: o saque REGISTRA o débito no ledger e devolve um protocolo. A transferência
 * para conta bancária depende de gateway externo, que está fora do MVP (ADR 0004). O débito ser
 * gravado agora é o que importa para a integridade — o saldo sai da carteira no momento do pedido,
 * não quando o dinheiro chega, senão a mesma quantia poderia ser sacada duas vezes na janela entre
 * pedido e liquidação.
 *
 * <p>Só BRL. XP não é sacável por definição, e TOKEN é moeda comunitária sem lastro externo —
 * permitir saque de token seria convertê-lo em dinheiro, que é exatamente o que o ADR 0004 separa.
 */
@Service
public class SaqueService {

  private static final String CARTEIRA_AUSENTE = "Carteira não encontrada.";

  private final CarteiraRepository carteiraRepository;
  private final LivroRazaoService livroRazaoService;
  private final PoliticaCarteira politica;

  public SaqueService(
      CarteiraRepository carteiraRepository,
      LivroRazaoService livroRazaoService,
      PoliticaCarteira politica) {
    this.carteiraRepository = carteiraRepository;
    this.livroRazaoService = livroRazaoService;
    this.politica = politica;
  }

  @Auditavel(acao = "SAQUE_SOLICITADO", entidade = "carteira")
  @Transactional
  public SaqueResponse sacar(UUID usuarioId, BigDecimal valorBrl, String chaveDoCliente) {
    Instant agora = Instant.now();

    if (valorBrl.compareTo(politica.saqueMinimoBrl()) < 0) {
      throw new RegraNegocioVioladaException(
          "Saque mínimo é R$ " + politica.saqueMinimoBrl() + ".");
    }

    UUID carteiraId =
        carteiraRepository
            .buscarIdPorUsuario(usuarioId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));

    // LOCK → SONDAR → VALIDAR → ESCREVER.
    Carteira carteira =
        carteiraRepository
            .buscarParaAtualizar(carteiraId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));

    String chave = ChaveIdempotencia.saque(usuarioId, chaveDoCliente);

    Optional<Lancamento> existente = livroRazaoService.consultar(chave);
    if (existente.isPresent()) {
      return new SaqueResponse(existente.get().getId(), carteira.getSaldoBrl(), true);
    }

    if (carteira.getSaldoBrl().compareTo(valorBrl) < 0) {
      throw new RegraNegocioVioladaException(
          "Saldo de R$ "
              + carteira.getSaldoBrl()
              + " é insuficiente para sacar R$ "
              + valorBrl
              + ".");
    }

    Lancamento lancamento =
        livroRazaoService.registrar(
            carteira,
            new Movimento(
                SinalLancamento.DEBITO,
                MotivoLancamento.SAQUE,
                valorBrl,
                0L,
                null,
                null,
                chave,
                null,
                agora));

    return new SaqueResponse(lancamento.getId(), carteira.getSaldoBrl(), false);
  }
}
