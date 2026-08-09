package com.omnitribo.compartilhado.dominio;

import com.omnitribo.compartilhado.api.TipoProblema;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class DominioException extends RuntimeException {

  private final HttpStatus httpStatus;

  public DominioException(HttpStatus httpStatus, String mensagem) {
    super(mensagem);
    this.httpStatus = httpStatus;
  }

  public HttpStatus getHttpStatus() {
    return httpStatus;
  }

  /**
   * Valor do campo {@code type} do RFC 9457 para esta exceção.
   *
   * <p>Sobrescrito por cada subclasse com um tipo específico. A implementação padrão deriva do
   * status, e existe para quem lança {@code DominioException} direto — hoje só o fluxo de
   * autenticação, que precisa de 401 sem revelar qual das causas ocorreu. O efeito é que nenhuma
   * resposta de erro do sistema sai com {@code about:blank}, mesmo sem subclasse dedicada.
   */
  public URI getTipo() {
    return TipoProblema.deStatus(httpStatus);
  }

  /**
   * Campos NUMÉRICOS de extensão do RFC 9457, acrescentados ao corpo do erro.
   *
   * <p>Existe porque {@code type} responde "qual foi a causa" e {@code detail} responde "o que
   * dizer ao humano" — mas nenhum dos dois responde "quanto". A tela de check-in precisa escrever
   * "você está a 180 m; aproxime-se para até 50 m", e os únicos caminhos para isso seriam parsear o
   * {@code detail} — proibido, porque é copy que muda a cada revisão — ou receber os números
   * separados. É este método.
   *
   * <p>Vazio por padrão: a esmagadora maioria das recusas não tem número a dar, e sobrescrever é
   * opt-in. O mapa é imutável e serializado direto no corpo, então só entram valores que o cliente
   * pode ver — nunca id interno, SQL ou nome de classe.
   */
  public Map<String, Object> getPropriedades() {
    return Map.of();
  }
}
