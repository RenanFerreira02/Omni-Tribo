package com.omnitribo.carteira.infra;

import com.omnitribo.compartilhado.api.DadosPessoaisDoUsuario;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Seção "lancamentos" da exportação LGPD: o extrato completo do titular.
 *
 * <p>{@code chave_idempotencia} NÃO sai. É material de controle da API, não dado do titular, e
 * publicá-la permitiria a quem tivesse o arquivo forjar o replay de uma operação de valor.
 *
 * <p>{@code contraparte_carteira_id} também não: identificaria com quem a pessoa transacionou, e
 * esse é dado do OUTRO titular. O extrato diz que houve uma transferência recebida, não de quem.
 */
@Component
public class DadosPessoaisCarteira implements DadosPessoaisDoUsuario {

  private final JdbcClient jdbc;

  public DadosPessoaisCarteira(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String secao() {
    return "lancamentos";
  }

  @Override
  public List<Map<String, Object>> exportar(UUID usuarioId) {
    return jdbc.sql(
            """
            SELECT l.sinal, l.motivo, l.valor_brl, l.valor_tokens,
                   l.saldo_apos_brl, l.saldo_apos_tokens, l.missao_id, l.criado_em
              FROM lancamento l
              JOIN carteira c ON c.id = l.carteira_id
             WHERE c.usuario_id = :usuario
             ORDER BY l.criado_em DESC
            """)
        .param("usuario", usuarioId)
        .query()
        .listOfRows();
  }
}
