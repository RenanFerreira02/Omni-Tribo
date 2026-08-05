package com.omnitribo.identidade.infra;

import com.omnitribo.identidade.dominio.Consentimento;
import com.omnitribo.identidade.dominio.TipoConsentimento;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentimentoRepository extends JpaRepository<Consentimento, UUID> {

  List<Consentimento> findByUsuarioId(UUID usuarioId);

  Optional<Consentimento> findByUsuarioIdAndTipo(UUID usuarioId, TipoConsentimento tipo);
}
