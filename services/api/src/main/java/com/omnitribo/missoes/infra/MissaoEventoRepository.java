package com.omnitribo.missoes.infra;

import com.omnitribo.missoes.dominio.MissaoEvento;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Trilha append-only de transições. Hoje só ESCRITA: nenhum endpoint expõe o histórico, e a leitura
 * acontece por SQL direto (psql, testes de migration). Se um endpoint de trilha nascer, ele traz a
 * própria consulta — um finder sem chamador aqui só simularia que a leitura já existe.
 */
public interface MissaoEventoRepository extends JpaRepository<MissaoEvento, UUID> {}
