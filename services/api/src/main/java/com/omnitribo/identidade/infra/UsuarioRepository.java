package com.omnitribo.identidade.infra;

import com.omnitribo.identidade.dominio.EstadoDaConta;
import com.omnitribo.identidade.dominio.Usuario;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

  Optional<Usuario> findByEmail(String email);

  boolean existsByEmail(String email);

  boolean existsByHandle(String handle);

  /**
   * Tribo do usuário SEM materializar a entidade.
   *
   * <p>Projeção escalar de propósito: carregar o {@code Usuario} inteiro para ler um campo poria a
   * entidade no persistence context, e um {@code buscarParaAtualizar} posterior na mesma transação
   * devolveria a instância em cache sem reemitir o {@code SELECT ... FOR UPDATE}. A consulta de
   * afiliação roda antes do crédito, então essa ordem acontece de verdade.
   *
   * <p>Devolve {@code Optional} de duas causas indistinguíveis aqui — usuário inexistente e usuário
   * sem tribo. Quem precisa separá-las é {@code ConsultaAfiliacaoService.mesmaTribo}.
   */
  @Query("select u.triboId from Usuario u where u.id = :id")
  Optional<UUID> buscarTriboId(@Param("id") UUID id);

  /**
   * O que a autenticação precisa saber sobre a conta, a cada requisição.
   *
   * <p>Projeção pela MESMA razão de {@code buscarTriboId}, e aqui com consequência maior: esta
   * consulta roda no filtro, antes de tudo. Materializar {@code Usuario} deixaria a entidade no
   * persistence context e faria o {@code buscarParaAtualizar} de qualquer operação de valor
   * devolver a instância em cache sem reemitir o {@code FOR UPDATE} — o lock sumiria em toda
   * requisição autenticada, sem que teste nenhum acusasse.
   *
   * <p>Busca por PK indexada: ~0,1 ms no miss do cache, e o cache absorve o resto.
   */
  @Query(
      """
      select new com.omnitribo.identidade.dominio.EstadoDaConta(
                 u.id, u.email, u.papel, u.status, u.anonimizadoEm)
        from Usuario u
       where u.id = :id
      """)
  Optional<EstadoDaConta> buscarEstadoDaConta(@Param("id") UUID id);

  /**
   * {@code SELECT ... FOR UPDATE} na linha do usuário, para a concessão de XP.
   *
   * <p>Sem isto, duas conclusões simultâneas do mesmo executor colidem na {@code @Version} de
   * {@code Usuario} e uma delas vira 409 depois de já ter gravado o crédito. Ver {@code
   * ProgressaoUsuario.concederXp}.
   *
   * <p>Última na ordem global {@code missao} → {@code carteira} → {@code usuario}.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from Usuario u where u.id = :id")
  Optional<Usuario> buscarParaAtualizar(@Param("id") UUID id);

  /**
   * Ids, dentre os dados, cujo XP alcança o limiar.
   *
   * <p>Compara XP e não a coluna {@code nivel}: aquela é cache recalculado a cada concessão, e
   * barrar alguém por cache defasado negaria acesso a quem já tem o XP, sem que a pessoa tenha o
   * que fazer a respeito. O limiar vem de {@code RegraNivel.xpParaNivel}, então a fórmula continua
   * num lugar só.
   */
  @Query("select u.id from Usuario u where u.id in :ids and u.xp >= :xpMinimo")
  List<UUID> idsComXpMinimo(@Param("ids") Collection<UUID> ids, @Param("xpMinimo") long xpMinimo);
}
