package com.omnitribo.carteira.infra;

import com.omnitribo.carteira.dominio.Lancamento;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LancamentoRepository extends JpaRepository<Lancamento, UUID> {

  List<Lancamento> findByCarteiraIdOrderByCriadoEmAsc(UUID carteiraId);

  Optional<Lancamento> findByChaveIdempotencia(String chaveIdempotencia);
}
