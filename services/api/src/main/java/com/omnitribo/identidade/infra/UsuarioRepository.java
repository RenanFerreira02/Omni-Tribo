package com.omnitribo.identidade.infra;

import com.omnitribo.identidade.dominio.Usuario;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {

  Optional<Usuario> findByEmail(String email);

  Optional<Usuario> findByHandle(String handle);

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
}
