package com.omnitribo.geolocalizacao.api;

import com.omnitribo.compartilhado.api.TipoProblema;
import com.omnitribo.compartilhado.dominio.RegraNegocioVioladaException;
import java.net.URI;

/**
 * Check-in recusado por uma regra antifraude. 422, como qualquer regra de negócio violada — o que
 * muda é o {@code type}.
 *
 * <p>Herda de {@link RegraNegocioVioladaException} de propósito: o status HTTP continua sendo 422,
 * e a ordem de checagem 403 → sondagem de idempotência → 409 → 422 do check-in fica intacta. Esta
 * classe não muda o QUE aconteceu, só torna a causa legível por máquina. Ver ADR 0010.
 *
 * <p>Uma classe com {@code switch}, e não três classes irmãs, porque as três compartilham status,
 * construtor e semântica; o que as distingue é exatamente um valor. Três classes só multiplicariam
 * a superfície a manter quando o enum crescer.
 *
 * <p>É lançada pelo controller DEPOIS do commit, nunca de dentro do serviço — a linha da rejeição
 * em {@code checkin} é a trilha antifraude e precisa sobreviver justamente às tentativas recusadas.
 * Ver {@code ResultadoRegistroCheckin}.
 */
public class CheckinRejeitadoException extends RegraNegocioVioladaException {

  private final MotivoRejeicaoCheckin motivo;

  public CheckinRejeitadoException(MotivoRejeicaoCheckin motivo, String mensagem) {
    super(mensagem);
    this.motivo = motivo;
  }

  public MotivoRejeicaoCheckin getMotivo() {
    return motivo;
  }

  @Override
  public URI getTipo() {
    return switch (motivo) {
      case LOCALIZACAO_SIMULADA -> TipoProblema.CHECKIN_LOCALIZACAO_SIMULADA;
      case ACURACIA_INSUFICIENTE -> TipoProblema.CHECKIN_ACURACIA_INSUFICIENTE;
      case FORA_DO_RAIO -> TipoProblema.CHECKIN_FORA_DO_RAIO;
    };
  }
}
