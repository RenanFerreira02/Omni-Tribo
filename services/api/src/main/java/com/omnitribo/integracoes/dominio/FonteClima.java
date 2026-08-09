package com.omnitribo.integracoes.dominio;

import com.omnitribo.integracoes.api.ClimaResponse;
import java.math.BigDecimal;

/**
 * Provedor de condição meteorológica.
 *
 * <p>Interface para que a política — cache, arredondamento da coordenada, degradação — fique no
 * serviço e o provedor seja substituível sem tocá-la. Também é o que permite testar o serviço sem
 * rede e testar o cliente HTTP sem contexto Spring.
 */
public interface FonteClima {

  /** Lança {@code ServicoExternoIndisponivelException} quando o provedor falha. */
  ClimaResponse consultar(BigDecimal lat, BigDecimal lon);
}
