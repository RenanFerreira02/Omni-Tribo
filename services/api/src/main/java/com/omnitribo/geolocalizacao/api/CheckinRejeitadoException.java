package com.omnitribo.geolocalizacao.api;

import com.omnitribo.compartilhado.api.TipoProblema;
import com.omnitribo.compartilhado.dominio.RegraNegocioVioladaException;
import java.math.BigDecimal;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

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
 *
 * <h2>Por que a exceção carrega números</h2>
 *
 * <p>O {@code type} diz ao app QUE regra falhou, e isso basta para escolher a reação. Não basta
 * para escrever a frase: "você está a 180 m do ponto; aproxime-se para até 50 m" precisa de 180 e
 * de 50. Os dois caminhos alternativos são piores — parsear o {@code detail} é proibido (é copy,
 * muda a cada revisão) e repetir os limites no cliente cria uma segunda fonte de verdade que
 * diverge no primeiro ajuste do servidor. Então os números saem como campos de extensão do RFC
 * 9457, via {@link #getPropriedades()}.
 */
public class CheckinRejeitadoException extends RegraNegocioVioladaException {

  private final MotivoRejeicaoCheckin motivo;
  private final transient Map<String, Object> propriedades;

  public CheckinRejeitadoException(MotivoRejeicaoCheckin motivo, String mensagem) {
    this(motivo, mensagem, Map.of());
  }

  private CheckinRejeitadoException(
      MotivoRejeicaoCheckin motivo, String mensagem, Map<String, Object> propriedades) {
    super(mensagem);
    this.motivo = motivo;
    this.propriedades = Map.copyOf(propriedades);
  }

  /**
   * Monta a exceção já com os números que a tela precisa para orientar o usuário.
   *
   * <p>Cada motivo leva só o que é acionável para ELE. Localização simulada não ganha número nenhum
   * porque não há distância a percorrer nem precisão a melhorar — a ação é desligar o mock, e um
   * "você está a 3 m" ao lado disso só confundiria.
   *
   * <p>Argumentos nulos são tolerados e simplesmente não viram campo: no replay de uma rejeição
   * antiga, nem toda medida foi persistida.
   */
  public static CheckinRejeitadoException de(
      MotivoRejeicaoCheckin motivo,
      String mensagem,
      BigDecimal distanciaM,
      Integer raioM,
      BigDecimal acuraciaM) {

    Map<String, Object> props = new LinkedHashMap<>();
    switch (motivo) {
      case FORA_DO_RAIO -> {
        poe(props, "distanciaM", distanciaM);
        poe(props, "raioM", raioM);
      }
      case ACURACIA_INSUFICIENTE -> {
        poe(props, "acuraciaM", acuraciaM);
        poe(props, "acuraciaMaximaM", LimitesCheckin.ACURACIA_MAXIMA_M);
      }
      case LOCALIZACAO_SIMULADA -> {
        /* Nenhum número é acionável aqui. */
      }
    }
    return new CheckinRejeitadoException(motivo, mensagem, props);
  }

  private static void poe(Map<String, Object> destino, String chave, Object valor) {
    if (valor != null) {
      destino.put(chave, valor);
    }
  }

  public MotivoRejeicaoCheckin getMotivo() {
    return motivo;
  }

  @Override
  public Map<String, Object> getPropriedades() {
    return propriedades;
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
