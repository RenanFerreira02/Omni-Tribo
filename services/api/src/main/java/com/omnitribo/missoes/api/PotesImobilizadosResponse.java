package com.omnitribo.missoes.api;

import com.omnitribo.compartilhado.api.PaginaResponse;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * O diagnóstico completo: quanto está preso, e quais missões o prendem.
 *
 * <p><b>O resumo viaja junto da página de propósito.</b> Um instrumento que exige uma segunda
 * chamada para responder "quanto token está preso" é instrumento pela metade — e o número total NÃO
 * é derivável da página, que traz no máximo 100 das linhas. {@code totalElementos} responderia
 * quantas missões, nunca quantos tokens.
 *
 * @param resumo contagem, soma e o limiar que produziu as duas
 * @param pagina as missões, da mais antiga para a mais recente pelo marco de cada status
 */
@Schema(description = "Potes imobilizados: resumo agregado mais a página de missões")
public record PotesImobilizadosResponse(
    ResumoPotesImobilizados resumo, PaginaResponse<PoteImobilizadoResponse> pagina) {}
