package com.omnitribo.carteira.infra;

import com.omnitribo.carteira.dominio.Beneficio;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BeneficioRepository extends JpaRepository<Beneficio, UUID> {

  /**
   * Benefícios ativos dos parceiros informados.
   *
   * <p>Filtra só o BENEFÍCIO; quem filtra o PARCEIRO é quem monta a lista de ids — a consulta
   * geoespacial já devolve apenas ativos, e o caminho por tribo usa {@code
   * findByTriboIdAndAtivoTrue}. Um benefício ativo de parceiro desligado não pode aparecer, e é
   * essa combinação que a V906 semeia como fixture.
   */
  List<Beneficio> findByParceiroIdInAndAtivoTrue(Collection<UUID> parceiroIds);
}
