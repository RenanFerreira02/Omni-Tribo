package com.omnitribo.carteira.dominio;

import com.omnitribo.carteira.infra.CarteiraRepository;
import com.omnitribo.carteira.infra.LancamentoRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * O ÚNICO ponto do sistema onde um saldo muda (ADR 0008).
 *
 * <p>{@code lancamento} é a verdade, append-only; {@code carteira.saldo_*} é projeção, escrita na
 * MESMA transação. Correção é estorno — o banco revoga UPDATE e DELETE no ledger.
 *
 * <p><b>Contrato do chamador:</b> já segura {@code PESSIMISTIC_WRITE} sobre a carteira, como
 * primeira leitura dela na transação. Sob READ COMMITTED o {@code FOR UPDATE} relê a versão mais
 * nova da linha, e serializa quem poderia produzir a mesma chave. Ordem: locks → sonda → valida →
 * escreve.
 *
 * <p>Nada aqui captura violação de {@code uk_lancamento_idempotencia}: se disparar, a serialização
 * falhou — é defeito e sobe 500 (e a transação já estaria rollback-only). {@code REQUIRES_NEW} é
 * proibido: com o FOR UPDATE seguro, pediria uma segunda conexão — deadlock de pool.
 */
@Service
public class LivroRazaoService {

  private final CarteiraRepository carteiraRepository;
  private final LancamentoRepository lancamentoRepository;

  public LivroRazaoService(
      CarteiraRepository carteiraRepository, LancamentoRepository lancamentoRepository) {
    this.carteiraRepository = carteiraRepository;
    this.lancamentoRepository = lancamentoRepository;
  }

  /**
   * Grava um movimento no ledger e atualiza a projeção. {@code MANDATORY}: fora de transação o lock
   * do contrato não existiria.
   *
   * @param carteira entidade JÁ TRAVADA pelo chamador
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Lancamento registrar(Carteira carteira, Movimento movimento) {
    if (movimento.sinal() == SinalLancamento.CREDITO) {
      carteira.creditar(movimento.valorBrl(), movimento.valorTokens());
    } else {
      // Guarda de erro de programação: o chamador já recusou com 422 sob o lock.
      carteira.debitar(movimento.valorBrl(), movimento.valorTokens());
    }

    Lancamento lancamento =
        new Lancamento(
            UUID.randomUUID(),
            carteira.getId(),
            movimento.sinal(),
            movimento.motivo(),
            movimento.valorBrl(),
            movimento.valorTokens(),
            movimento.missaoId(),
            movimento.contraparteCarteiraId(),
            movimento.chaveIdempotencia(),
            // Saldos depois da mutação: a linha registra o estado resultante, auditável sem somar
            // tudo.
            carteira.getSaldoBrl(),
            carteira.getSaldoTokens(),
            movimento.mensagem(),
            movimento.agora());

    // saveAndFlush: o INSERT chega ao banco agora — a constraint é avaliada a tempo, e o teste de
    // rollback não afirma a ausência de uma linha que nunca foi tentada.
    Lancamento gravado = lancamentoRepository.saveAndFlush(lancamento);
    carteiraRepository.save(carteira);
    return gravado;
  }

  /** Sonda um lançamento pela chave derivada. Só é autoritativa sob o lock do contrato. */
  @Transactional(readOnly = true)
  public Optional<Lancamento> consultar(String chaveIdempotencia) {
    return lancamentoRepository.findByChaveIdempotencia(chaveIdempotencia);
  }
}
