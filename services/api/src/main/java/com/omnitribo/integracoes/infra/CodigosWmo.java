package com.omnitribo.integracoes.infra;

/**
 * Tradução dos códigos WMO de condição do tempo para português.
 *
 * <p>Fica no servidor, e não no app, por uma razão de manutenção: o código é vocabulário do
 * PROVEDOR. Trocar de provedor muda esta tabela num arquivo e não exige publicar versão nova do
 * aplicativo na loja — e um app antigo instalado continuaria mostrando a tradução errada para
 * sempre.
 *
 * <p>Códigos agrupados por faixa em vez de um por um: a diferença entre "chuvisco leve" e "chuvisco
 * moderado" não muda o que o usuário faz, e a granularidade fina só criaria strings para revisar.
 */
final class CodigosWmo {

  private CodigosWmo() {}

  static String descricao(int codigo) {
    return switch (codigo) {
      case 0 -> "Céu limpo";
      case 1, 2 -> "Parcialmente nublado";
      case 3 -> "Nublado";
      case 45, 48 -> "Neblina";
      case 51, 53, 55, 56, 57 -> "Chuvisco";
      case 61, 63, 65, 66, 67 -> "Chuva";
      case 71, 73, 75, 77 -> "Neve";
      case 80, 81, 82 -> "Pancadas de chuva";
      case 85, 86 -> "Pancadas de neve";
      case 95, 96, 99 -> "Tempestade";
      // Código fora da tabela não é erro: o provedor pode acrescentar valores, e um texto neutro é
      // melhor do que derrubar a consulta por causa da legenda.
      default -> "Condição indisponível";
    };
  }
}
