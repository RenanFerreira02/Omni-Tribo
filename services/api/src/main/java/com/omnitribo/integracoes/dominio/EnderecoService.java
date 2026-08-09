package com.omnitribo.integracoes.dominio;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.integracoes.api.EnderecoResponse;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * CEP com cache.
 *
 * <p>Sem TTL, ao contrário do clima, e a assimetria é o ponto: logradouro de um CEP não muda em dez
 * minutos — quando muda, muda uma vez por década. O teto de entradas é a única política de despejo
 * necessária.
 *
 * <p>O NEGATIVO também é cacheado, como {@code Optional.empty()}. Sem isso, um usuário digitando um
 * CEP errado dispararia uma chamada externa por tecla enquanto corrige — exatamente o caso mais
 * frequente de erro num formulário.
 */
@Service
public class EnderecoService {

  private static final int MAXIMO_ENTRADAS = 10_000;

  private final FonteEndereco fonte;
  private final Cache<String, Optional<EnderecoResponse>> cache =
      Caffeine.newBuilder().maximumSize(MAXIMO_ENTRADAS).build();

  public EnderecoService(FonteEndereco fonte) {
    this.fonte = fonte;
  }

  /**
   * @throws RecursoNaoEncontradoException CEP bem formado que não existe — 404, e NÃO o 503 de
   *     provedor fora do ar. As duas causas pedem reações opostas do usuário: corrigir o número, ou
   *     tentar de novo mais tarde.
   */
  public EnderecoResponse buscar(String cep) {
    return cache
        .get(cep, fonte::buscar)
        .orElseThrow(() -> new RecursoNaoEncontradoException("CEP não encontrado."));
  }
}
