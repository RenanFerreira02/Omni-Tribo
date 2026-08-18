package com.omnitribo.carteira.infra;

import com.omnitribo.carteira.dominio.Carteira;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CarteiraRepository extends JpaRepository<Carteira, UUID> {

  Optional<Carteira> findByUsuarioId(UUID usuarioId);

  /**
   * Resolve usuário → id da carteira SEM materializar a entidade.
   *
   * <p>Existe por causa da armadilha documentada em {@link #buscarParaAtualizar}: {@link
   * #findByUsuarioId} devolve uma {@code Carteira} gerenciada e, com isso, envenena o persistence
   * context — o {@code buscarParaAtualizar} seguinte devolveria a instância em cache sem reemitir o
   * {@code SELECT ... FOR UPDATE}, e o lock jamais seria adquirido. Uma projeção escalar não põe
   * nada no contexto, então resolver o id por aqui é seguro.
   */
  @Query("select c.id from Carteira c where c.usuarioId = :usuarioId")
  Optional<UUID> buscarIdPorUsuario(@Param("usuarioId") UUID usuarioId);

  /**
   * {@code SELECT ... FOR UPDATE} na linha da carteira. É o mecanismo que serializa toda mutação de
   * saldo — crédito de conclusão, transferência, saque, financiamento e estorno.
   *
   * <p>Por que lock pessimista e não só a {@code @Version} da entidade: o mesmo raciocínio de
   * {@code MissaoRepository.buscarParaAtualizar}. Com {@code @Version}, o perdedor só descobre a
   * colisão no flush, depois de o método de negócio ter retornado, e o INSERT no ledger já foi
   * emitido para ser desfeito. Com {@code FOR UPDATE} ele bloqueia por microssegundos, relê o saldo
   * já commitado e decide certo na primeira tentativa.
   *
   * <p>E há uma segunda razão, específica do ledger: o lock é o que fecha a janela entre SONDAR a
   * chave de idempotência e INSERIR. Duas requisições com a mesma chave são serializadas por esta
   * linha, então a sondagem do perdedor roda depois do commit do vencedor e enxerga a linha dele. A
   * UNIQUE {@code uk_lancamento_idempotencia} é barreira final, não o mecanismo — se ela disparar,
   * este invariante deixou de valer e é defeito, não corrida a recuperar.
   *
   * <p>ORDEM GLOBAL DE LOCK: {@code missao} → {@code carteira} (id CRESCENTE) → {@code usuario}.
   * Nenhum caminho pode desviar. Travar duas carteiras sempre do menor id para o maior é o que
   * impede o deadlock A→B / B→A: uma transação só espera por quem segura um id MENOR, então o grafo
   * de espera não fecha ciclo. Ver {@code TransferenciaService}.
   *
   * <p>CHAMADA OBRIGATORIAMENTE COMO PRIMEIRA LEITURA DESTA ENTIDADE NA TRANSAÇÃO: se a {@code
   * Carteira} já estiver no persistence context, o Hibernate devolve a instância em cache sem
   * reemitir o {@code SELECT ... FOR UPDATE} — o teste passa, o lock não existe e o invariante
   * sumiu em silêncio.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Carteira c where c.id = :id")
  Optional<Carteira> buscarParaAtualizar(@Param("id") UUID id);
}
