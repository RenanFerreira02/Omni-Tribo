package com.omnitribo.identidade.infra;

import com.omnitribo.identidade.dominio.Consentimento;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentimentoRepository extends JpaRepository<Consentimento, UUID> {

  /**
   * Histórico completo, do mais recente para o mais antigo. O estado ATUAL de cada tipo é a
   * primeira linha daquele tipo, resolvida em Java por {@code ConsentimentoService}.
   *
   * <p>A ordenação não é conveniência: a tabela é append-only — cada mudança de escolha grava uma
   * linha nova, para que "quando ele consentiu, e sob qual versão do texto?" continue respondível.
   * Um finder por {@code (usuarioId, tipo)} sem ordenação e sem limite estouraria com {@code
   * NonUniqueResultException} na PRIMEIRA revogação de qualquer usuário.
   */
  List<Consentimento> findByUsuarioIdOrderByCriadoEmDesc(UUID usuarioId);
}
