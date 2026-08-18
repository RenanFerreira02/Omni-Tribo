package com.omnitribo.integracoes.dominio;

import com.omnitribo.integracoes.api.EnderecoResponse;
import java.util.Optional;

/**
 * Provedor de endereço por CEP.
 *
 * <p>{@code Optional.empty()} significa "CEP bem formado que não existe" — resultado legítimo, que
 * vira 404. Falha do provedor é outra coisa e sobe como {@code
 * ServicoExternoIndisponivelException}: confundir as duas faria a tela dizer "CEP não encontrado"
 * quando o problema é a internet, e o usuário reescreveria um CEP correto várias vezes.
 */
public interface FonteEndereco {

  Optional<EnderecoResponse> buscar(String cep);
}
