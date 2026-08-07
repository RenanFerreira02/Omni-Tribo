package com.omnitribo.missoes.infra;

import com.omnitribo.missoes.dominio.CategoriaMissao;

/**
 * Chave do cache de proximidade.
 *
 * <p>A coordenada crua não entra: dois pedidos do mesmo aparelho parado quase nunca trazem o mesmo
 * par lat/lon, e a taxa de acerto seria zero. Entra a célula de geohash de precisão 7 (~150 m), que
 * agrupa consultas vizinhas na mesma entrada.
 *
 * <p>NÃO CONTÉM O ID DO USUÁRIO, e essa é uma propriedade que precisa continuar verdadeira. Vale
 * hoje porque a busca só devolve missões ABERTA, que são visíveis a qualquer autenticado — o
 * resultado não depende de quem pergunta. No dia em que o radar passar a devolver qualquer coisa
 * específica do solicitante (rascunho próprio, missões restritas à tribo, distância personalizada),
 * este record precisa ganhar o usuarioId NO MESMO COMMIT que fizer a mudança. Sem isso o cache
 * passa a servir a um usuário o resultado calculado para outro.
 */
public record ChaveProximidade(
    String celulaGeohash, int raioMetros, CategoriaMissao categoria, int limite) {}
