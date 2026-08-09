package com.omnitribo.carteira.dominio;

import com.omnitribo.compartilhado.api.TipoProblema;
import com.omnitribo.compartilhado.dominio.RegraNegocioVioladaException;
import java.net.URI;

/**
 * Saque recusado porque o recurso está desligado por configuração ({@code
 * app.carteira.saque-habilitado}), e não porque o pedido do usuário tem algo de errado.
 *
 * <p>Ganha {@code type} próprio porque a tela reage diferente: não é um alerta de erro que o
 * usuário possa corrigir tentando outro valor, é um estado do produto — o BRL saiu do ciclo de
 * missões (ADR 0009) e não há mais como ganhá-lo. Confundir isso com "saldo insuficiente" faria o
 * app sugerir ao usuário juntar saldo para uma operação que não vai reabrir. Ver ADR 0010.
 *
 * <p>Os demais 422 do {@code SaqueService} — saldo insuficiente, limites — continuam genéricos,
 * pelo mesmo critério invertido: neles a tela faz a mesma coisa, que é exibir o {@code detail}.
 */
public class SaqueDesabilitadoException extends RegraNegocioVioladaException {

  public SaqueDesabilitadoException(String mensagem) {
    super(mensagem);
  }

  @Override
  public URI getTipo() {
    return TipoProblema.SAQUE_DESABILITADO;
  }
}
