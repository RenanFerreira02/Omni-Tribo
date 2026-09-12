package com.omnitribo.identidade.infra;

import com.omnitribo.identidade.dominio.Patrocinador;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Acesso a {@link Patrocinador}.
 *
 * <p><b>NÃO acrescente {@code findByTransportadoraSlug}.</b> Ele existiu aqui até 2026-09-09,
 * declarado e nunca chamado — nem em {@code main}, nem em {@code test}. Código morto já seria
 * motivo para remover; o motivo real é que ele é uma armadilha. Um derived query desse nome devolve
 * o patrocinador <b>sem filtrar {@code ativo}</b>, que é exatamente a distinção que o javadoc de
 * {@link #buscarUsuarioIdAtivoPorSlug} diz não poder ficar a cargo do chamador. Quem precisar do
 * slug vai alcançar o finder de nome óbvio, não a query de nome comprido — e converterá entrega
 * falida financiada por um contrato de patrocínio já encerrado, sem erro nenhum apontando a causa.
 *
 * <p>Se algum dia for preciso a ENTIDADE e não o id, o método novo carrega {@code Ativo} no nome e
 * o predicado na query, como este aqui.
 */
public interface PatrocinadorRepository extends JpaRepository<Patrocinador, UUID> {

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
