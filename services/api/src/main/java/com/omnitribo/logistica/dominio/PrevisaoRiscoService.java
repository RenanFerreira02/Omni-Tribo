package com.omnitribo.logistica.dominio;

import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * A borda do modelo: resolve o que vem de fora e delega a conta a {@link PrevisorDeRisco}.
 *
 * <p>Fino de propósito. Tudo que é REGRA (a fórmula, os fatores, as faixas) vive na função pura,
 * que é testável sem Spring e sem banco; o que vive aqui é apenas resolução de configuração e
 * imputação — as duas coisas que a função pura não pode fazer sem deixar de ser pura.
 *
 * <p>Não é transacional e não toca banco. É o que permite chamá-lo <b>antes</b> de abrir a
 * transação do webhook, que é obrigatório: aquela transação segura {@code SELECT ... FOR UPDATE}
 * sobre {@code ponto_custodia}, e consultar clima ali dentro pediria uma segunda conexão sob lock —
 * o mesmo desenho que derrubou o check-in da F6 e levou o projeto a proibir {@code REQUIRES_NEW} no
 * caminho de valor.
 */
@Service
public class PrevisaoRiscoService {

  private final ParametrosRisco parametros;

  public PrevisaoRiscoService(ParametrosRisco parametros) {
    this.parametros = parametros;
  }

  /**
   * Estima o risco, imputando o que não veio.
   *
   * <p>A imputação usa a MÉDIA da partição de treino, o que produz z-score exatamente 0 e portanto
   * contribuição nula: o modelo passa a não ter opinião sobre a característica ausente, em vez de
   * chutar para cima ou para baixo. Quais características foram imputadas viaja no resultado — um
   * score que se apoiou em suposição é menos confiável, e esconder isso seria desonesto.
   */
  public ResultadoRisco prever(DadosParaPrevisao dados) {
    Set<CaracteristicaRisco> imputadas = EnumSet.noneOf(CaracteristicaRisco.class);

    double chuvaMm =
        dados.chuvaMm() != null
            ? dados.chuvaMm()
            : mediaDoTreino(CaracteristicaRisco.CHUVA_MM, imputadas);
    double temperaturaC =
        dados.temperaturaC() != null
            ? dados.temperaturaC()
            : mediaDoTreino(CaracteristicaRisco.TEMPERATURA_C, imputadas);

    FeaturesEntrega features =
        new FeaturesEntrega(
            dados.horaDoDia(),
            dados.diaSemana(),
            dados.tipoEndereco(),
            parametros.taxaDaFaixaDeCep(dados.cep()),
            dados.pesoKg(),
            dados.volumeL(),
            chuvaMm,
            temperaturaC,
            dados.tentativasAnteriores());

    return PrevisorDeRisco.avaliar(features, parametros, imputadas);
  }

  private double mediaDoTreino(CaracteristicaRisco c, Set<CaracteristicaRisco> imputadas) {
    imputadas.add(c);
    return parametros.padronizacao().get(c).media();
  }
}
