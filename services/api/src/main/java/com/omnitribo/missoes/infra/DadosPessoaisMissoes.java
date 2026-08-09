package com.omnitribo.missoes.infra;

import com.omnitribo.compartilhado.api.DadosPessoaisDoUsuario;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Seção "missoes" da exportação LGPD: o que a pessoa criou e o que executou.
 *
 * <p>O papel dela em cada missão vai explícito, em vez de duas seções. É a informação que o titular
 * quer ("eu pedi ou eu fiz?") e a que uma lista de títulos não responde.
 *
 * <p><b>Sem coordenadas, por duas razões independentes.</b> A regra do projeto é que toda função
 * PostGIS vive numa única classe ({@code ConsultasGeoespaciais}, ADR 0007), e um {@code ST_Y} aqui
 * a quebraria. E o endereço em texto — CEP, logradouro, bairro, cidade, UF — é o que uma PESSOA lê
 * no próprio arquivo de exportação; um par de decimais não acrescenta nada a quem exerce um
 * direito.
 */
@Component
public class DadosPessoaisMissoes implements DadosPessoaisDoUsuario {

  private final JdbcClient jdbc;

  public DadosPessoaisMissoes(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String secao() {
    return "missoes";
  }

  @Override
  public List<Map<String, Object>> exportar(UUID usuarioId) {
    return jdbc.sql(
            """
            SELECT CASE WHEN m.criador_id = :usuario THEN 'CRIADOR' ELSE 'EXECUTOR' END AS papel,
                   m.titulo, m.descricao, m.categoria, m.status, m.complexidade,
                   m.xp_recompensa, m.tokens_recompensa,
                   m.cep, m.logradouro, m.bairro, m.cidade, m.uf,
                   m.janela_inicio, m.janela_fim,
                   m.criada_em, m.aceita_em, m.concluida_em
              FROM missao m
             WHERE m.criador_id = :usuario OR m.executor_id = :usuario
             ORDER BY m.criada_em DESC
            """)
        .param("usuario", usuarioId)
        .query()
        .listOfRows();
  }
}
