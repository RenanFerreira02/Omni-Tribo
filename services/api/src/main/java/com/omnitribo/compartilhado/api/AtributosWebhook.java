package com.omnitribo.compartilhado.api;

/**
 * Chaves de atributo que o filtro de webhook publica na requisição, para o controller ler.
 *
 * <p>Vive em {@code compartilhado/api} e não junto do filtro por imposição do ArchUnit: o filtro
 * está em {@code compartilhado/infra}, que é adaptador PRIVADO e inalcançável de qualquer módulo de
 * negócio. O controller do webhook é de {@code logistica} e precisa da chave, então a chave é
 * porta, não infraestrutura.
 *
 * <p>O que passa por aqui já foi VERIFICADO. É o que separa a alegação (o cabeçalho que o cliente
 * mandou) da prova (a assinatura HMAC que o filtro conferiu) — o controller nunca deve ler o
 * cabeçalho cru, do mesmo modo que nunca lê identidade de usuário fora do JWT.
 */
public final class AtributosWebhook {

  private AtributosWebhook() {}

  /** Slug da transportadora, em minúsculas, cuja assinatura foi conferida com sucesso. */
  public static final String TRANSPORTADORA = "omnitribo.webhook.transportadora";
}
