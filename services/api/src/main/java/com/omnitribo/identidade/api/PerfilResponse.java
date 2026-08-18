package com.omnitribo.identidade.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Perfil completo do usuário autenticado.
 *
 * <p><b>Endpoint separado de {@code GET /auth/me}, e não uma ampliação dele.</b> O {@code /auth/me}
 * é chamado no boot do app e se resolve só dos claims do JWT, sem tocar o banco; enriquecê-lo
 * trocaria uma checagem barata de identidade por uma consulta com joins em toda abertura do
 * aplicativo. São duas perguntas diferentes, com custos e frequências diferentes.
 *
 * <p>{@code nivel} vem DERIVADO do XP por {@code RegraNivel} na hora da leitura, e não da coluna
 * {@code usuario.nivel} — que é cache recalculado a cada concessão. Se os dois divergirem, quem
 * está certo é a fórmula, e é ela que o perfil mostra.
 *
 * <p>Nenhum campo sensível: sem hash de senha, sem e-mail de terceiros, sem coordenada.
 */
@Schema(description = "Perfil do usuário autenticado, com progressão e conquistas")
public record PerfilResponse(
    UUID id,
    String nome,
    String email,
    String handle,
    String papel,
    @Schema(description = "Tribo do usuário; nula enquanto ele não escolheu uma")
        TriboResponse tribo,
    long xp,
    int nivel,
    @Schema(description = "XP que marcou a entrada no nível atual") long xpNivelAtual,
    @Schema(
            description =
                "XP necessário para o próximo nível — com o atual, dá a barra de progresso")
        long xpProximoNivel,
    int streak,
    List<ConquistaResponse> conquistas) {

  /** List.copyOf: a lista guardada é imutável e o acessor não tem o que expor. */
  public PerfilResponse {
    conquistas = List.copyOf(conquistas);
  }
}
