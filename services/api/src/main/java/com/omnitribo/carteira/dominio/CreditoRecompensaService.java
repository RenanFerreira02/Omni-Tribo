package com.omnitribo.carteira.dominio;

import com.omnitribo.carteira.api.ComandoCreditoConclusao;
import com.omnitribo.carteira.api.CreditoRecompensa;
import com.omnitribo.carteira.api.ResultadoCredito;
import com.omnitribo.carteira.infra.CarteiraRepository;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Implementação de {@link CreditoRecompensa}. Ver o javadoc da porta para o contrato. */
@Service
public class CreditoRecompensaService implements CreditoRecompensa {

  private static final String CARTEIRA_AUSENTE = "Carteira do executor não encontrada.";

  private final CarteiraRepository carteiraRepository;
  private final LivroRazaoService livroRazaoService;

  public CreditoRecompensaService(
      CarteiraRepository carteiraRepository, LivroRazaoService livroRazaoService) {
    this.carteiraRepository = carteiraRepository;
    this.livroRazaoService = livroRazaoService;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public ResultadoCredito creditarConclusao(ComandoCreditoConclusao comando) {
    // Resolve o id SEM materializar a entidade: findByUsuarioId poria uma Carteira gerenciada no
    // persistence context, e o buscarParaAtualizar seguinte devolveria essa instância em cache sem
    // reemitir o SELECT ... FOR UPDATE — o lock nunca aconteceria.
    UUID carteiraId =
        carteiraRepository
            .buscarIdPorUsuario(comando.executorId())
            .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));

    // LOCK PRIMEIRO. Ordem global: missao (já travada pelo chamador) → carteira.
    Carteira carteira =
        carteiraRepository
            .buscarParaAtualizar(carteiraId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));

    // SONDAR DEPOIS DO LOCK. É esta ordem que elimina a corrida: duas conclusões concorrentes da
    // mesma missão estão serializadas pela linha da missão, então a segunda enxerga o lançamento da
    // primeira aqui e sai por replay, sem nunca chegar ao INSERT.
    Optional<Lancamento> existente = livroRazaoService.consultar(comando.chaveIdempotencia());
    if (existente.isPresent()) {
      return new ResultadoCredito(
          existente.get().getId(), carteira.getSaldoBrl(), carteira.getSaldoTokens(), true);
    }

    Lancamento lancamento =
        livroRazaoService.registrar(
            carteira,
            new Movimento(
                SinalLancamento.CREDITO,
                MotivoLancamento.RECOMPENSA_MISSAO,
                comando.valorBrl(),
                comando.tokens(),
                comando.missaoId(),
                null,
                comando.chaveIdempotencia(),
                null,
                comando.agora()));

    return new ResultadoCredito(
        lancamento.getId(), carteira.getSaldoBrl(), carteira.getSaldoTokens(), false);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ResultadoCredito> consultar(String chaveIdempotencia) {
    return livroRazaoService
        .consultar(chaveIdempotencia)
        .map(
            l ->
                new ResultadoCredito(l.getId(), l.getSaldoAposBrl(), l.getSaldoAposTokens(), true));
  }
}
