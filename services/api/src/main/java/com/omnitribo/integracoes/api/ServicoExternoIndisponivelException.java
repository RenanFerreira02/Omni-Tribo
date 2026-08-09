package com.omnitribo.integracoes.api;

import com.omnitribo.compartilhado.api.TipoProblema;
import com.omnitribo.compartilhado.dominio.DominioException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * Um provedor EXTERNO não respondeu a tempo, ou respondeu de forma que não conseguimos usar.
 *
 * <p>503, e não 500, porque a causa não é um defeito nosso e a resposta certa do cliente é outra:
 * 500 pede "reporte o erro", 503 pede "siga sem isso, e talvez tente de novo depois". O card de
 * clima some da tela; o campo de endereço continua editável à mão. Ver ADR 0011.
 *
 * <p>A mensagem NUNCA repassa o corpo, o status ou o host do provedor. Isso vazaria a topologia das
 * nossas dependências para qualquer cliente e, no caso de um provedor que ecoa a requisição, os
 * nossos próprios parâmetros de volta.
 */
public class ServicoExternoIndisponivelException extends DominioException {

  public ServicoExternoIndisponivelException(String mensagem) {
    super(HttpStatus.SERVICE_UNAVAILABLE, mensagem);
  }

  @Override
  public URI getTipo() {
    return TipoProblema.SERVICO_EXTERNO_INDISPONIVEL;
  }
}
