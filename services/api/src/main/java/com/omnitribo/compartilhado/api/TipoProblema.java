package com.omnitribo.compartilhado.api;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * Catálogo dos valores do campo {@code type} do RFC 9457. É contrato público: o app mobile decide o
 * que fazer olhando para ele.
 *
 * <p><b>Por que não deixar {@code about:blank}.</b> {@code about:blank} é legal no RFC e significa
 * "sem tipo específico": o cliente fica só com o número do status, e status HTTP é ambíguo por
 * natureza — dois 409 daqui querem dizer coisas diferentes (transição inválida pede "recarregue a
 * tela"; colisão de versão pede "tente de novo"). A alternativa que o app tomaria sem isto é
 * discriminar pelo {@code detail}, que é texto em português voltado a humano: muda sem aviso e
 * quebraria o cliente na primeira revisão de copy.
 *
 * <p><b>Granularidade de hoje: uma URI por CLASSE de erro, não por causa.</b> Todo 422 é {@link
 * #REGRA_NEGOCIO_VIOLADA}, seja saldo insuficiente ou check-in fora do raio — o {@code detail} é
 * que separa os dois, para humano. Se o app precisar ramificar entre causas de um mesmo status, o
 * caminho é uma subclasse de {@code DominioException} sobrescrevendo {@code getTipo()} com uma URI
 * nova daqui; não é parsear {@code detail}.
 *
 * <p>As URIs <b>não precisam ser dereferenciáveis</b> (RFC 9457 §3.1.1) — são identificadores
 * estáveis, não endereços. O domínio é fictício de propósito, para deixar claro que ninguém deve
 * tentar buscá-las na rede.
 *
 * <p><b>Estabilidade:</b> uma vez publicada, uma URI daqui não muda. Renomear uma constante é
 * refactor; mudar o texto da URI é quebra de contrato com todo app já instalado.
 */
public final class TipoProblema {

  private static final String BASE = "https://omnitribo.dev/problemas/";

  /** Corpo, parâmetro ou header malformado — falha de Bean Validation. */
  public static final URI REQUISICAO_INVALIDA = URI.create(BASE + "requisicao-invalida");

  /** Ausência de credencial válida: token faltando, malformado ou expirado. */
  public static final URI NAO_AUTENTICADO = URI.create(BASE + "nao-autenticado");

  /** Autenticado, mas sem permissão sobre este recurso. */
  public static final URI ACESSO_NEGADO = URI.create(BASE + "acesso-negado");

  /** Recurso inexistente — ou existente e invisível para quem perguntou. */
  public static final URI NAO_ENCONTRADO = URI.create(BASE + "nao-encontrado");

  /**
   * 409: a operação não cabe no estado ATUAL, mas caberia em outro. Distinto de {@link
   * #REGRA_NEGOCIO_VIOLADA}, que é 422 — ver o javadoc de {@code RegraNegocioVioladaException}.
   */
  public static final URI TRANSICAO_INVALIDA = URI.create(BASE + "transicao-invalida");

  /** 422: cabe no estado, mas os dados não satisfazem a regra (saldo, pote, raio, janela). */
  public static final URI REGRA_NEGOCIO_VIOLADA = URI.create(BASE + "regra-negocio-violada");

  /** 409 de colisão de {@code @Version}: alguém alterou o recurso no meio do caminho. */
  public static final URI CONFLITO_CONCORRENCIA = URI.create(BASE + "conflito-concorrencia");

  /** 429 de rate limit ou de bloqueio progressivo. Acompanha {@code Retry-After}. */
  public static final URI LIMITE_REQUISICOES = URI.create(BASE + "limite-requisicoes");

  /** 501: contrato publicado, implementação em fase futura. */
  public static final URI NAO_IMPLEMENTADO = URI.create(BASE + "nao-implementado");

  /** 500. Sem detalhe acionável de propósito — o traceId é o que liga a resposta ao log. */
  public static final URI ERRO_INTERNO = URI.create(BASE + "erro-interno");

  /**
   * Fallback para {@code DominioException} lançada sem subclasse (hoje, o fluxo de autenticação).
   *
   * <p>Deriva do status para que uma exceção nova nunca chegue ao cliente como {@code about:blank}:
   * o pior caso vira um tipo genérico porém estável, e não a ausência de tipo.
   */
  public static URI deStatus(HttpStatus status) {
    return switch (status) {
      case BAD_REQUEST -> REQUISICAO_INVALIDA;
      case UNAUTHORIZED -> NAO_AUTENTICADO;
      case FORBIDDEN -> ACESSO_NEGADO;
      case NOT_FOUND -> NAO_ENCONTRADO;
      case CONFLICT -> TRANSICAO_INVALIDA;
      case UNPROCESSABLE_ENTITY -> REGRA_NEGOCIO_VIOLADA;
      case TOO_MANY_REQUESTS -> LIMITE_REQUISICOES;
      case NOT_IMPLEMENTED -> NAO_IMPLEMENTADO;
      default -> ERRO_INTERNO;
    };
  }

  private TipoProblema() {}
}
