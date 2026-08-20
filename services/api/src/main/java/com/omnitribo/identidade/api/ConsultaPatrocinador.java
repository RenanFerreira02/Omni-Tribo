package com.omnitribo.identidade.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta pela qual {@code logistica} descobre quem financia as missões de uma transportadora.
 *
 * <p>Existe pelo motivo de sempre: o ArchUnit proíbe {@code logistica} de alcançar {@code
 * identidade.dominio}, onde vivem {@code Patrocinador} e o repositório dele. Só tipos da JDK na
 * assinatura — devolver a entidade aqui reprovaria o teste de arquitetura e, pior, exporia uma
 * entidade JPA a outro módulo.
 */
public interface ConsultaPatrocinador {

  /**
   * O titular de carteira que patrocina esta transportadora, se houver um ATIVO.
   *
   * <p>{@code Optional.empty()} cobre os três casos de uma vez — slug sem patrocinador, patrocínio
   * desativado e slug desconhecido — e isso é deliberado: os três produzem o mesmo desfecho
   * SEM_PATROCINIO, e distingui-los daria à transportadora informação sobre a carteira de um
   * terceiro sem nenhum ganho operacional. Ela precisa saber que reenviar não adianta, não por quê.
   *
   * <p><b>Não diz nada sobre SALDO.</b> Quem sabe se o patrocinador consegue pagar é a carteira,
   * sob lock, no instante da conversão — responder aqui seria uma leitura sem lock, e entre ela e o
   * débito caberia outro webhook consumindo o mesmo saldo.
   *
   * @param transportadoraSlug o slug VERIFICADO pelo HMAC, nunca o cabeçalho cru. Comparado em
   *     minúsculas, como o filtro o publica.
   */
  Optional<UUID> usuarioIdDoPatrocinadorAtivo(String transportadoraSlug);
}
