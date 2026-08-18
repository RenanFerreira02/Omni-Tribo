package com.omnitribo.carteira.infra;

import com.omnitribo.carteira.dominio.Lancamento;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LancamentoRepository extends JpaRepository<Lancamento, UUID> {

  /**
   * Sondagem de replay. Autoritativa SOMENTE sob o lock da linha serializadora — ver {@code
   * CarteiraRepository.buscarParaAtualizar}. Chamada sem esse lock, é um palpite com corrida.
   */
  Optional<Lancamento> findByChaveIdempotencia(String chaveIdempotencia);

  /**
   * Extrato paginado. Servido por {@code idx_lancamento_carteira_criado (carteira_id, criado_em
   * DESC)} da V13.
   *
   * <p>Substitui o antigo {@code findByCarteiraIdOrderByCriadoEmAsc}, que devolvia {@code List} sem
   * limite: numa carteira com histórico real isso carregaria o ledger inteiro na memória por
   * requisição.
   */
  Page<Lancamento> findByCarteiraId(UUID carteiraId, Pageable pageable);

  /**
   * Soma dos tokens ENVIADOS por uma carteira desde um instante, para o teto por janela.
   *
   * <p>{@code coalesce} porque {@code SUM} de conjunto vazio é NULL, e uma carteira que nunca
   * transferiu é o caso mais comum — sem isso, a primeira transferência de todo usuário quebraria
   * com NPE ao desempacotar o {@code Long}.
   *
   * <p>Consulta racy em tese, correta na prática pelo mesmo motivo da verificação de saldo: roda
   * DEPOIS do lock da carteira do remetente, então duas transferências do mesmo remetente estão
   * serializadas e a segunda enxerga a linha da primeira.
   */
  @Query(
      """
      select coalesce(sum(l.valorTokens), 0)
      from Lancamento l
      where l.carteiraId = :carteiraId
        and l.motivo = com.omnitribo.carteira.dominio.MotivoLancamento.TRANSFERENCIA_ENVIADA
        and l.criadoEm >= :desde
      """)
  long somarTransferenciasEnviadasDesde(
      @Param("carteiraId") UUID carteiraId, @Param("desde") Instant desde);

  /**
   * Financiamentos de uma missão, para o estorno em CANCELADA/EXPIRADA. Uma missão tem poucos
   * financiadores, então {@code List} sem paginação é adequado aqui.
   */
  @Query(
      """
      select l from Lancamento l
      where l.missaoId = :missaoId
        and l.motivo = com.omnitribo.carteira.dominio.MotivoLancamento.FINANCIAMENTO_TRIBO
      order by l.criadoEm asc
      """)
  List<Lancamento> buscarFinanciamentosDaMissao(@Param("missaoId") UUID missaoId);
}
