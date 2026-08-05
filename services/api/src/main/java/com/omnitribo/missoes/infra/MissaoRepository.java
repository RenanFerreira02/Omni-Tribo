package com.omnitribo.missoes.infra;

import com.omnitribo.missoes.dominio.Missao;
import com.omnitribo.missoes.dominio.StatusMissao;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MissaoRepository extends JpaRepository<Missao, UUID> {

  List<Missao> findByStatus(StatusMissao status);

  List<Missao> findByExecutorId(UUID executorId);

  List<Missao> findByCriadorId(UUID criadorId);
}
