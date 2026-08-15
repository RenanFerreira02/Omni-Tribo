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
 * <p><b>Granularidade: uma URI por REAÇÃO DE UI, não por classe de erro nem por causa.</b> O
 * critério está no ADR 0010. {@link #REGRA_NEGOCIO_VIOLADA} continua sendo o 422 padrão — saldo
 * insuficiente, pote insuficiente, janela vencida —, porque nesses casos a tela faz a mesma coisa:
 * exibe o {@code detail}. Ganham URI própria as causas em que a tela age DIFERENTE: as três
 * rejeições de check-in (desligar o mock, aproximar-se, procurar céu aberto) e o saque desligado
 * por configuração. Precisa de uma nova? O caminho é uma subclasse de {@code DominioException}
 * sobrescrevendo {@code getTipo()}; nunca parsear {@code detail}.
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

  /**
   * 422: saque desligado por configuração ({@code app.carteira.saque-habilitado}).
   *
   * <p>Separado do 422 genérico porque não é falha do pedido do usuário nem algo que ele possa
   * corrigir tentando de outro jeito — é um recurso fechado. A tela precisa de estado próprio, e
   * não de um alerta de erro. Ver ADR 0009 e ADR 0010.
   */
  public static final URI SAQUE_DESABILITADO = URI.create(BASE + "saque-desabilitado");

  /** 422: check-in recusado porque o dispositivo reportou localização simulada. */
  public static final URI CHECKIN_LOCALIZACAO_SIMULADA =
      URI.create(BASE + "checkin-localizacao-simulada");

  /** 422: check-in recusado porque o raio de erro do GPS não sustenta afirmação de presença. */
  public static final URI CHECKIN_ACURACIA_INSUFICIENTE =
      URI.create(BASE + "checkin-acuracia-insuficiente");

  /** 422: check-in recusado porque a distância medida excede o raio da missão. */
  public static final URI CHECKIN_FORA_DO_RAIO = URI.create(BASE + "checkin-fora-do-raio");

  /**
   * 422: a missão exige nível maior do que o de quem tentou aceitar.
   *
   * <p>URI própria porque a reação de UI não é a dos outros 422. Nos demais, a tela pede para
   * corrigir o pedido e tentar de novo; aqui não há o que corrigir — a mesma requisição volta a
   * funcionar sozinha quando o usuário acumular XP. A tela mostra quanto falta e leva ao perfil.
   *
   * <p>Acompanha as extensões {@code nivelExigido} e {@code nivelAtual}, como o check-in fora do
   * raio acompanha {@code distanciaM} e {@code raioM}: ler número de dentro do {@code detail}
   * acoplaria a UI à revisão de copy do servidor.
   */
  public static final URI NIVEL_INSUFICIENTE = URI.create(BASE + "nivel-insuficiente");

  /** 409 de colisão de {@code @Version}: alguém alterou o recurso no meio do caminho. */
  public static final URI CONFLITO_CONCORRENCIA = URI.create(BASE + "conflito-concorrencia");

  /** 429 de rate limit ou de bloqueio progressivo. Acompanha {@code Retry-After}. */
  public static final URI LIMITE_REQUISICOES = URI.create(BASE + "limite-requisicoes");

  /** 500. Sem detalhe acionável de propósito — o traceId é o que liga a resposta ao log. */
  public static final URI ERRO_INTERNO = URI.create(BASE + "erro-interno");

  /**
   * 503: um provedor EXTERNO (clima, CEP) não respondeu a tempo ou respondeu errado.
   *
   * <p>URI própria, e não {@link #ERRO_INTERNO}, porque a reação de UI é outra e específica: o card
   * de clima simplesmente some, e o campo de endereço continua editável à mão. Tratar isso como 500
   * faria a tela mostrar "erro inesperado" para uma degradação prevista e inofensiva. Ver ADR 0010,
   * que define a granularidade como uma URI por REAÇÃO DE UI, e ADR 0011.
   */
  public static final URI SERVICO_EXTERNO_INDISPONIVEL =
      URI.create(BASE + "servico-externo-indisponivel");

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
      default -> ERRO_INTERNO;
    };
  }

  private TipoProblema() {}
}
