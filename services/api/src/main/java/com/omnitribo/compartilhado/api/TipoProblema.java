package com.omnitribo.compartilhado.api;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * Catálogo do campo {@code type} do RFC 9457 — contrato público: o app decide o que fazer por ele,
 * nunca pelo {@code detail} (texto que muda) nem só pelo status (dois 409 pedem reações
 * diferentes).
 *
 * <p>Uma URI por REAÇÃO DE UI (ADR 0010): {@link #REGRA_NEGOCIO_VIOLADA} é o 422 padrão; ganha URI
 * própria só a causa em que a tela age diferente. As URIs são identificadores, não endereços, e uma
 * vez publicadas não mudam.
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

  /** 409: não cabe no estado ATUAL, mas caberia em outro (diferente do 422 de regra de negócio). */
  public static final URI TRANSICAO_INVALIDA = URI.create(BASE + "transicao-invalida");

  /** 422: cabe no estado, mas os dados não satisfazem a regra (saldo, pote, raio, janela). */
  public static final URI REGRA_NEGOCIO_VIOLADA = URI.create(BASE + "regra-negocio-violada");

  /** 422: saque desligado por configuração — recurso fechado, a tela mostra estado próprio. */
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
   * 422: nível insuficiente para aceitar. A mesma requisição volta a funcionar com mais XP; a tela
   * mostra quanto falta. Extensões {@code nivelExigido} e {@code nivelAtual}.
   */
  public static final URI NIVEL_INSUFICIENTE = URI.create(BASE + "nivel-insuficiente");

  /**
   * 422: o pote não cobre a recompensa. O pedido está certo; as saídas são um vizinho financiar ou
   * editar para só XP (ADR 0035). Extensões {@code recompensaTokens} e {@code poteTokens}.
   */
  public static final URI POTE_INSUFICIENTE = URI.create(BASE + "pote-insuficiente");

  /** 409 de colisão de {@code @Version}: alguém alterou o recurso no meio do caminho. */
  public static final URI CONFLITO_CONCORRENCIA = URI.create(BASE + "conflito-concorrencia");

  /** 429 de rate limit ou de bloqueio progressivo. Acompanha {@code Retry-After}. */
  public static final URI LIMITE_REQUISICOES = URI.create(BASE + "limite-requisicoes");

  /** 500. Sem detalhe acionável de propósito — o traceId é o que liga a resposta ao log. */
  public static final URI ERRO_INTERNO = URI.create(BASE + "erro-interno");

  /**
   * 503: provedor externo (clima, CEP) falhou — a tela esconde o recurso, não mostra erro (ADR
   * 0011).
   */
  public static final URI SERVICO_EXTERNO_INDISPONIVEL =
      URI.create(BASE + "servico-externo-indisponivel");

  /** Fallback derivado do status: exceção nova nunca sai como {@code about:blank}. */
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
