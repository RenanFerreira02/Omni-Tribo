package com.omnitribo.carteira.dominio;

import java.security.SecureRandom;

/**
 * Gera o código de 8 caracteres que a pessoa mostra no balcão do parceiro.
 *
 * <h2>Isto NÃO é um segredo criptográfico</h2>
 *
 * <p>E o desenho não finge que é. Quem autoriza a baixa de um resgate é um ADMIN, pelo id — o
 * código serve para o humano do balcão casar o papel na mão do cliente com a linha na tela. Não há
 * HMAC nem assinatura aqui de propósito: inventar criptografia para um identificador de balcão
 * daria a ele aparência de credencial, e alguém acabaria confiando nisso para autorizar alguma
 * coisa.
 *
 * <p>{@link SecureRandom} mesmo assim, por higiene: um {@code Random} comum é previsível a partir
 * de poucas saídas, e um código adivinhável convidaria alguém a tentar a sorte no balcão. É defesa
 * em profundidade sobre um identificador, não a segurança do resgate.
 *
 * <h2>O alfabeto</h2>
 *
 * <p>Sem {@code 0/O} e sem {@code 1/I/L}. O código é lido em voz alta e digitado por gente cansada,
 * num balcão de padaria — os pares visualmente ambíguos são a maior fonte de erro nesse cenário, e
 * eliminá-los custa dois caracteres de espaço amostral. Sobram 31 símbolos em 8 posições, ~2,5×10¹²
 * combinações: espaço de sobra para um catálogo de bairro.
 */
public final class GeradorCodigoRetirada {

  private static final String ALFABETO = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
  private static final int TAMANHO = 8;
  private static final SecureRandom ALEATORIO = new SecureRandom();

  private GeradorCodigoRetirada() {}

  public static String gerar() {
    StringBuilder codigo = new StringBuilder(TAMANHO);
    for (int i = 0; i < TAMANHO; i++) {
      codigo.append(ALFABETO.charAt(ALEATORIO.nextInt(ALFABETO.length())));
    }
    return codigo.toString();
  }
}
