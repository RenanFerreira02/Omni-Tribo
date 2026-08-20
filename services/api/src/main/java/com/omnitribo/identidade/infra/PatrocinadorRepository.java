package com.omnitribo.identidade.infra;

import com.omnitribo.identidade.dominio.Patrocinador;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatrocinadorRepository extends JpaRepository<Patrocinador, UUID> {

  Optional<Patrocinador> findByTransportadoraSlug(String transportadoraSlug);

  boolean existsByTransportadoraSlug(String transportadoraSlug);

  /**
   * Resolve slug → {@code usuario_id} do patrocinador ATIVO, sem materializar a entidade.
   *
   * <p>Projeção escalar de propósito, e a razão é a mesma de {@code
   * CarteiraRepository.buscarIdPorUsuario}: o resultado desta consulta é usado logo em seguida para
   * travar a carteira do patrocinador com {@code SELECT ... FOR UPDATE}. Materializar entidade
   * alguma aqui evita qualquer chance de envenenar o persistence context no caminho de valor.
   *
   * <p>O filtro por {@code ativo} mora na QUERY e não no chamador: patrocínio encerrado precisa
   * produzir exatamente o mesmo desfecho de patrocinador inexistente — SEM_PATROCINIO —, e deixar a
   * distinção para o chamador é convidar um {@code if} esquecido a converter missão financiada por
   * um contrato que acabou.
   */
  @Query(
      """
      select p.usuarioId from Patrocinador p
      where p.transportadoraSlug = :slug
        and p.ativo = true
      """)
  Optional<UUID> buscarUsuarioIdAtivoPorSlug(@Param("slug") String slug);
}
