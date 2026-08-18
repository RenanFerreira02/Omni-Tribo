package com.omnitribo.geolocalizacao.dominio;

/** Como a presença foi comprovada. Espelhado num {@code CHECK} da V4. */
public enum MetodoCheckin {

  /** Único valor produzido hoje: {@code RegistroCheckinService} sempre grava GPS. */
  GPS,

  /**
   * RESERVADO. Código lido no ponto de custódia, para o caso em que o GPS é fraco (dentro de loja,
   * subsolo) — exatamente onde a acurácia costuma estourar o teto e o check-in por GPS é recusado.
   *
   * <p>Depende do fluxo de entrega da F8, que é quem daria um código a cada ponto. Enquanto isso, é
   * uma constante que o banco aceita e que nenhum caminho de código escreve.
   */
  QR
}
