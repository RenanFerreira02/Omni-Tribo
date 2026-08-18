package com.omnitribo.identidade.infra;

import com.omnitribo.compartilhado.api.DadosPessoaisDoUsuario;
import com.omnitribo.identidade.dominio.RegraNivel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Seção "identidade" da exportação LGPD: quem a pessoa é para o sistema, e o que ela consentiu.
 *
 * <p>{@code senha_hash} NÃO sai, e a exclusão é deliberada. O titular tem direito aos dados dele,
 * não ao material criptográfico que protege a conta — e um hash exportado é um alvo de força bruta
 * offline entregue de bandeja, num arquivo que a pessoa provavelmente vai guardar sem cuidado.
 */
@Component
public class DadosPessoaisIdentidade implements DadosPessoaisDoUsuario {

  private final JdbcClient jdbc;

  public DadosPessoaisIdentidade(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String secao() {
    return "identidade";
  }

  @Override
  public List<Map<String, Object>> exportar(UUID usuarioId) {
    List<Map<String, Object>> cadastro =
        jdbc.sql(
                """
                SELECT u.nome, u.email, u.handle, u.xp, u.streak, u.rating,
                       u.papel, u.status, u.criado_em AS cadastrado_em,
                       t.nome AS tribo, t.bairro AS bairro_da_tribo
                  FROM usuario u
                  LEFT JOIN tribo t ON t.id = u.tribo_id
                 WHERE u.id = :usuario
                """)
            .param("usuario", usuarioId)
            .query()
            .listOfRows();

    List<Map<String, Object>> consentimentos =
        jdbc.sql(
                """
                SELECT tipo, concedido, versao_texto, criado_em
                  FROM consentimento
                 WHERE usuario_id = :usuario
                 ORDER BY criado_em DESC
                """)
            .param("usuario", usuarioId)
            .query()
            .listOfRows();

    // Uma linha só, com o histórico de consentimento aninhado: o arquivo é lido por uma pessoa, e
    // duas seções separadas para "quem sou" e "o que aceitei" só espalhariam a mesma resposta.
    return cadastro.stream()
        .map(
            linha -> {
              Map<String, Object> completo = new LinkedHashMap<>(linha);
              // Nível DERIVADO do XP, não lido de `usuario.nivel`.
              //
              // A coluna é cache recalculado a cada concessão, e as duas leituras divergiam de
              // verdade: para a alice do seed, GET /usuarios/me respondia 2 (derivado por
              // RegraNivel) e esta exportação respondia 3 (a coluna). Duas respostas para a mesma
              // pergunta, e a que ia no arquivo de direito do titular era a errada.
              //
              // Quem manda é a FÓRMULA — a coluna existe para evitar recalcular em listagem, não
              // para ser fonte de verdade. Ver PerfilService, que já derivava.
              completo.put("nivel", RegraNivel.nivelPara(((Number) linha.get("xp")).longValue()));
              completo.put("consentimentos", consentimentos);
              return completo;
            })
        .toList();
  }
}
