package com.omnitribo.carteira.api;

import com.omnitribo.carteira.dominio.Beneficio;
import com.omnitribo.carteira.dominio.Parceiro;
import com.omnitribo.carteira.dominio.TipoBeneficio;
import java.util.UUID;

/**
 * Um item do catálogo, com o parceiro embutido.
 *
 * @param distanciaM metros até o parceiro, DERIVADA pelo PostGIS nesta consulta. Nula no recorte
 *     por tribo, onde o critério é pertencimento e não geografia. Nunca vem de coluna — distância
 *     depende de onde está quem pergunta
 * @param custoTokens o preço VIGENTE. O resgate congela o cobrado na própria linha de resgate,
 *     então este número pode mudar sem reinterpretar o passado
 */
public record BeneficioResponse(
    UUID id,
    String titulo,
    String descricao,
    long custoTokens,
    TipoBeneficio tipo,
    UUID parceiroId,
    String parceiroNome,
    String bairro,
    Double distanciaM) {

  public static BeneficioResponse de(Beneficio b, Parceiro p, Double distanciaM) {
    return new BeneficioResponse(
        b.getId(),
        b.getTitulo(),
        b.getDescricao(),
        b.getCustoTokens(),
        b.getTipo(),
        p.getId(),
        p.getNome(),
        p.getBairro(),
        distanciaM);
  }
}
