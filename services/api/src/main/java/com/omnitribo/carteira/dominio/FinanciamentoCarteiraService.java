package com.omnitribo.carteira.dominio;

import com.omnitribo.carteira.api.EstornoPote;
import com.omnitribo.carteira.api.FinanciamentoMissao;
import com.omnitribo.carteira.api.ResultadoFinanciamento;
import com.omnitribo.carteira.infra.CarteiraRepository;
import com.omnitribo.carteira.infra.LancamentoRepository;
import com.omnitribo.compartilhado.dominio.ChaveIdempotencia;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.compartilhado.dominio.RegraNegocioVioladaException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Lado carteira do financiamento: débito do financiador e estorno do pote. */
@Service
public class FinanciamentoCarteiraService implements FinanciamentoMissao, EstornoPote {

  private static final String CARTEIRA_AUSENTE = "Carteira não encontrada.";

  private final CarteiraRepository carteiraRepository;
  private final LancamentoRepository lancamentoRepository;
  private final LivroRazaoService livroRazaoService;

  public FinanciamentoCarteiraService(
      CarteiraRepository carteiraRepository,
      LancamentoRepository lancamentoRepository,
      LivroRazaoService livroRazaoService) {
    this.carteiraRepository = carteiraRepository;
    this.lancamentoRepository = lancamentoRepository;
    this.livroRazaoService = livroRazaoService;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public ResultadoFinanciamento debitar(
      UUID financiadorId, UUID missaoId, long tokens, String chaveIdempotencia, Instant agora) {

    UUID carteiraId =
        carteiraRepository
            .buscarIdPorUsuario(financiadorId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));

    // LOCK → SONDAR → VALIDAR → ESCREVER. Ver o javadoc de LivroRazaoService.
    Carteira carteira =
        carteiraRepository
            .buscarParaAtualizar(carteiraId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));

    Optional<Lancamento> existente = livroRazaoService.consultar(chaveIdempotencia);
    if (existente.isPresent()) {
      return new ResultadoFinanciamento(existente.get().getId(), carteira.getSaldoTokens(), true);
    }

    // Recusa ANTES de qualquer escrita: 422 tem de sair sem efeito colateral nenhum.
    if (carteira.getSaldoTokens() < tokens) {
      throw new RegraNegocioVioladaException(
          "Saldo de "
              + carteira.getSaldoTokens()
              + " tokens é insuficiente para financiar "
              + tokens
              + ".");
    }

    Lancamento lancamento =
        livroRazaoService.registrar(
            carteira,
            Movimento.deTokens(
                SinalLancamento.DEBITO,
                MotivoLancamento.FINANCIAMENTO_TRIBO,
                tokens,
                missaoId,
                null,
                chaveIdempotencia,
                null,
                agora));

    return new ResultadoFinanciamento(lancamento.getId(), carteira.getSaldoTokens(), false);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public long estornarFinanciadores(UUID missaoId, Instant agora) {
    List<Lancamento> financiamentos = lancamentoRepository.buscarFinanciamentosDaMissao(missaoId);
    long total = 0;

    // Ordenado por id da carteira para respeitar a ordem global de lock mesmo quando a mesma
    // pessoa financiou duas vezes ou quando duas missões estornam em paralelo no lote de expiração.
    List<UUID> carteiras =
        financiamentos.stream().map(Lancamento::getCarteiraId).distinct().sorted().toList();

    for (UUID carteiraId : carteiras) {
      long aDevolver =
          financiamentos.stream()
              .filter(l -> l.getCarteiraId().equals(carteiraId))
              .mapToLong(Lancamento::getValorTokens)
              .sum();

      String chave = ChaveIdempotencia.estornoFinanciamento(missaoId, carteiraId);

      Carteira carteira =
          carteiraRepository
              .buscarParaAtualizar(carteiraId)
              .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));

      // Já estornado num processamento anterior: conta no total, mas não credita de novo. Devolver
      // zero aqui faria o chamador acusar divergência justamente no caso correto.
      if (livroRazaoService.consultar(chave).isPresent()) {
        total += aDevolver;
        continue;
      }

      livroRazaoService.registrar(
          carteira,
          Movimento.deTokens(
              SinalLancamento.CREDITO,
              MotivoLancamento.ESTORNO,
              aDevolver,
              missaoId,
              null,
              chave,
              null,
              agora));
      total += aDevolver;
    }
    return total;
  }
}
