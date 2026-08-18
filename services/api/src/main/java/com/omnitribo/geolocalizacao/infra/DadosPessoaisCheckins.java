package com.omnitribo.geolocalizacao.infra;

import com.omnitribo.compartilhado.api.DadosPessoaisDoUsuario;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Seção "checkins" da exportação LGPD: o histórico de presença do titular.
 *
 * <p>É a seção mais sensível do arquivo — um histórico de localização é o que ele parece ser — e é
 * por isso mesmo que ela precisa estar aqui: dado sensível que a plataforma guarda é exatamente o
 * que o direito de acesso existe para revelar.
 *
 * <p>A COORDENADA não sai, e a distância medida sai. Duas razões: a regra do projeto mantém toda
 * função PostGIS numa única classe (ADR 0007), e {@code ponto} exigiria {@code ST_Y}/{@code ST_X}
 * aqui; e "você estava a 12 m da origem da missão X, nesta data" responde à pergunta do titular sem
 * materializar um rastro de coordenadas num arquivo que ele vai baixar e guardar.
 *
 * <p>{@code chave_idempotencia} não sai — é hash de controle, não dado pessoal.
 */
@Component
public class DadosPessoaisCheckins implements DadosPessoaisDoUsuario {

  private final JdbcClient jdbc;

  public DadosPessoaisCheckins(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String secao() {
    return "checkins";
  }

  @Override
  public List<Map<String, Object>> exportar(UUID usuarioId) {
    return jdbc.sql(
            """
            SELECT missao_id, acuracia_m, distancia_alvo_m, metodo, mock_detectado,
                   valido, motivo_rejeicao, criado_em
              FROM checkin
             WHERE usuario_id = :usuario
             ORDER BY criado_em DESC
            """)
        .param("usuario", usuarioId)
        .query()
        .listOfRows();
  }
}
