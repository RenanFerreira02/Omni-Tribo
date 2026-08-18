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
  public Optional<ResultadoFinanciamento> sondar(UUID financiadorId, String chaveIdempotencia) {
    Carteira carteira = travarCarteiraDe(financiadorId);
    return livroRazaoService
        .consultar(chaveIdempotencia)
        .map(l -> new ResultadoFinanciamento(l.getId(), carteira.getSaldoTokens(), true));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public ResultadoFinanciamento debitar(
      UUID financiadorId, UUID missaoId, long tokens, String chaveIdempotencia, Instant agora) {

    // NÃO reemite o SELECT ... FOR UPDATE, e aqui isso é CORRETO — leia antes de "consertar".
    //
    // `sondar` já travou esta carteira na MESMA transação, então o Hibernate devolve a instância do
    // persistence context sem ir ao banco. É a única ocorrência no projeto em que a armadilha da
    // primeira leitura é benigna: o lock existe, foi adquirido por quem sondou, e é ele que fecha a
    // corrida entre sondar e inserir. Trocar isto por uma leitura "mais segura" não acrescenta
    // proteção nenhuma e só gasta uma ida ao banco.
    Carteira carteira = travarCarteiraDe(financiadorId);

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

  /**
   * Resolve {@code usuarioId → carteiraId} por projeção escalar e então trava a linha.
   *
   * <p>{@code buscarIdPorUsuario}, nunca {@code findByUsuarioId}: materializar a {@code Carteira}
   * aqui a poria no persistence context e o {@code buscarParaAtualizar} seguinte devolveria a
   * instância em cache sem emitir o {@code FOR UPDATE} — o lock sumiria em silêncio.
   */
  private Carteira travarCarteiraDe(UUID financiadorId) {
    UUID carteiraId =
        carteiraRepository
            .buscarIdPorUsuario(financiadorId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));
    return carteiraRepository
        .buscarParaAtualizar(carteiraId)
        .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));
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
