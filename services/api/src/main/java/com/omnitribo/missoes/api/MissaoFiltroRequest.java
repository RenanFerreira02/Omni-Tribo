package com.omnitribo.missoes.api;

import com.omnitribo.missoes.dominio.CategoriaMissao;
import com.omnitribo.missoes.dominio.StatusMissao;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Sort;

/**
 * Filtros e paginação da listagem.
 *
 * <p>Não usamos o argument resolver de Pageable do Spring Data: ele aceita {@code
 * sort=qualquerPropriedade}, o que expõe o modelo interno e permite ordenar por coluna sem índice —
 * DoS barato. Aqui a ordenação é uma whitelist de enum.
 */
@Schema(description = "Filtros de listagem de missões")
public record MissaoFiltroRequest(
    StatusMissao status,
    CategoriaMissao categoria,
    @Size(max = 100) String cidade,
    @Size(max = 100) String bairro,
    @Schema(description = "CRIADAS = criei; EXECUTANDO = aceitei. Nulo traz todas as visíveis.")
        Escopo minhas,
    // Integer, não int: parâmetro de query ausente chega como null, e o binder do Spring não
    // consegue converter null para primitivo — toda listagem sem ?pagina= viraria 400.
    @Min(value = 0, message = "Página não pode ser negativa") Integer pagina,
    @Min(value = 1, message = "Tamanho mínimo de página é 1")
        @Max(value = 100, message = "Tamanho máximo de página é 100")
        Integer tamanho,
    CampoOrdenacao ordenarPor,
    Sort.Direction direcao) {

  public enum Escopo {
    CRIADAS,
    EXECUTANDO
  }

  /** Whitelist de ordenação — evita ORDER BY por propriedade arbitrária vinda do cliente. */
  public enum CampoOrdenacao {
    CRIADA_EM("criadaEm"),
    JANELA_FIM("janelaFim"),
    // VALOR_BRL saiu: ordenar por uma coluna que a ck_missao_economia (V15) obriga a ser ZERO em
    // toda linha é ordenação por constante — devolve a mesma ordem arbitrária sempre e sugere ao
    // cliente um critério que não existe.
    TOKENS_RECOMPENSA("tokensRecompensa"),
    XP_RECOMPENSA("xpRecompensa");

    private final String propriedade;

    CampoOrdenacao(String propriedade) {
      this.propriedade = propriedade;
    }

    public String propriedade() {
      return propriedade;
    }
  }

  /** Defaults aplicados antes da validação, que roda sobre o objeto já construído. */
  public MissaoFiltroRequest {
    if (pagina == null) {
      pagina = 0;
    }
    if (tamanho == null) {
      tamanho = 20;
    }
    if (ordenarPor == null) {
      ordenarPor = CampoOrdenacao.CRIADA_EM;
    }
    if (direcao == null) {
      direcao = Sort.Direction.DESC;
    }
  }
}
