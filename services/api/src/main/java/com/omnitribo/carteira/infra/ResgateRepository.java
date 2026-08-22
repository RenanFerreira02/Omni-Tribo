package com.omnitribo.carteira.infra;

import com.omnitribo.carteira.dominio.Resgate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResgateRepository extends JpaRepository<Resgate, UUID> {

  boolean existsByCodigoRetirada(String codigoRetirada);
}
