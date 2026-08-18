package com.omnitribo.identidade.infra;

import com.omnitribo.identidade.dominio.Consentimento;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

  /**
   * Quem, nestas tribos, concedeu TODOS estes tipos e não revogou depois.
   *
   * <p>Nativa, e não JPQL, por causa do {@code DISTINCT ON} — que é justamente o que resolve o
   * estado atual de uma tabela append-only sem trazer o histórico para a memória. JPQL não tem
   * equivalente, e a alternativa portável (subconsulta com {@code MAX(criado_em)} por tipo) faz
   * duas varreduras onde esta faz uma.
   *
   * <p>A subconsulta correlacionada conta quantos tipos EXIGIDOS estão atualmente concedidos para
   * cada usuário e compara com a quantidade pedida. É o que implementa a conjunção — quem concedeu
   * NOTIFICACAO mas revogou LOCALIZACAO fica de fora, e não haveria como expressar isso com um
   * simples {@code IN}.
   *
   * <p>Exclui INATIVO/SUSPENSO/BANIDO e conta anonimizada. Notificar uma conta anonimizada seria
   * escrever dado novo ligado a alguém que exerceu o direito de apagamento.
   */
  @Query(
      value =
          """
          SELECT u.id
            FROM usuario u
           WHERE u.tribo_id IN (:tribos)
             AND u.status = 'ATIVO'
             AND u.anonimizado_em IS NULL
             AND (SELECT COUNT(*)
                    FROM (SELECT DISTINCT ON (c.tipo) c.concedido
                            FROM consentimento c
                           WHERE c.usuario_id = u.id
                             AND c.tipo IN (:tipos)
                           ORDER BY c.tipo, c.criado_em DESC) atual
                   WHERE atual.concedido) = :exigidos
          """,
      nativeQuery = true)
  List<UUID> usuariosComConsentimento(
      @Param("tribos") Collection<UUID> triboIds,
      @Param("tipos") Collection<String> tipos,
      @Param("exigidos") long quantidadeExigida);
}
