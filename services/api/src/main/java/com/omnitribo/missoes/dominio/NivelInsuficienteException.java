package com.omnitribo.missoes.dominio;

import com.omnitribo.compartilhado.api.TipoProblema;
import com.omnitribo.compartilhado.dominio.RegraNegocioVioladaException;
import java.net.URI;
import java.util.Map;

/**
 * Aceite recusado por reputação insuficiente.
 *
 * <p>Ganha {@code type} próprio pelo critério do ADR 0010 — uma URI por REAÇÃO DE UI. A tela não
 * faz o que faz nos outros 422: ela mostra quanto falta e leva ao perfil, em vez de pedir para
 * corrigir o pedido e tentar de novo. Repetir a mesma requisição amanhã pode funcionar, sem que
 * nada no corpo mude, e é isso que separa este caso de "os dados enviados não satisfazem a regra".
 *
 * <p>O nível exigido e o atual vão em {@code getPropriedades()}, como extensões do ProblemDetail,
 * pelo mesmo motivo que o check-in fora do raio devolve {@code distanciaM} e {@code raioM}: o app
 * precisa montar a frase com números, e ler número de dentro do {@code detail} é acoplar a UI à
 * revisão de copy do servidor.
 */
public class NivelInsuficienteException extends RegraNegocioVioladaException {

  private final int nivelExigido;
  private final int nivelAtual;

  public NivelInsuficienteException(int nivelExigido, int nivelAtual) {
    super(
        "Esta missão exige nível "
            + nivelExigido
            + " e o seu é "
            + nivelAtual
            + ". Missões com custódia de encomenda de terceiros são restritas a membros com"
            + " reputação consolidada.");
    this.nivelExigido = nivelExigido;
    this.nivelAtual = nivelAtual;
  }

  @Override
  public URI getTipo() {
    return TipoProblema.NIVEL_INSUFICIENTE;
  }

  @Override
  public Map<String, Object> getPropriedades() {
    return Map.of("nivelExigido", nivelExigido, "nivelAtual", nivelAtual);
  }
}
