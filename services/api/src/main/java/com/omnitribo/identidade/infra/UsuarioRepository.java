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
   * Tribo do usuário por projeção escalar: materializar o {@code Usuario} o poria no persistence
   * context, e um {@code buscarParaAtualizar} posterior perderia o FOR UPDATE.
   */
  @Query("select u.triboId from Usuario u where u.id = :id")
  Optional<UUID> buscarTriboId(@Param("id") UUID id);

  /**
   * O que a autenticação precisa saber da conta, a cada requisição (ADR 0016). Projeção pelo mesmo
   * motivo — aqui o lock sumiria de toda operação de valor autenticada.
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
   * FOR UPDATE no usuário para a concessão de XP: sem ele, duas conclusões do mesmo executor
   * colidiriam na @Version depois do crédito. Último na ordem missao → carteira → usuario.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from Usuario u where u.id = :id")
  Optional<Usuario> buscarParaAtualizar(@Param("id") UUID id);

  /**
   * Busca por handle EXATO, sem caixa, só contas utilizáveis (ADR 0028). Igualdade e nunca {@code
   * like} — senão vira listagem. Usa o índice funcional da V27. Conta anonimizada ou suspensa não é
   * encontrável. A restrição de TRIBO é do chamador.
   */
  @Query(
      """
      select u from Usuario u
       where lower(u.handle) = lower(:handle)
         and u.status = com.omnitribo.identidade.dominio.StatusUsuario.ATIVO
         and u.anonimizadoEm is null
      """)
  Optional<Usuario> buscarPorHandleExato(@Param("handle") String handle);

  /** Ids cujo XP alcança o limiar — compara XP, não a coluna {@code nivel}, que é cache. */
  @Query("select u.id from Usuario u where u.id in :ids and u.xp >= :xpMinimo")
  List<UUID> idsComXpMinimo(@Param("ids") Collection<UUID> ids, @Param("xpMinimo") long xpMinimo);
}
