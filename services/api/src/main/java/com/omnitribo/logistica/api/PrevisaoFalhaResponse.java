package com.omnitribo.logistica.api;

import com.omnitribo.logistica.dominio.FaixaRisco;
import com.omnitribo.logistica.dominio.FatorRisco;
import com.omnitribo.logistica.dominio.ResultadoRisco;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Risco estimado, a faixa, e POR QUÊ.
 *
 * <p>{@code fatoresPrincipais} é o que separa este endpoint de um oráculo: sem ele o cliente
 * receria "78%" sem nada a fazer com isso. Com ele, dá para dizer qual condição mudar — combinar
 * outro horário, pedir confirmação do destinatário, escolher outro ponto.
 *
 * @param multiplicadorRecompensa quanto este risco multiplica a recompensa em TOKEN da missão que
 *     nascer desta entrega. Exposto para que o valor seja auditável antes de ser congelado.
 * @param featuresImputadas características cujo valor real não estava disponível e foram
 *     substituídas pela média do treino. Não vazia significa score menos confiável.
 * @param versaoModelo versão do artefato treinado que produziu este número. É o que permite
 *     responder depois "este multiplicador estava certo quando foi aplicado?".
 */
@Schema(description = "Probabilidade de falha de uma entrega, com os fatores que mais pesaram")
public record PrevisaoFalhaResponse(
    @Schema(
            description = "Probabilidade estimada de a entrega falhar, de 0 a 1",
            example = "0.7823")
        BigDecimal probabilidadeFalha,
    @Schema(example = "ALTO") FaixaRisco faixaRisco,
    @Schema(description = "Multiplicador da recompensa em TOKEN", example = "1.39")
        BigDecimal multiplicadorRecompensa,
    List<FatorResponse> fatoresPrincipais,
    @Schema(description = "Características imputadas por indisponibilidade do dado")
        List<String> featuresImputadas,
    int versaoModelo) {

  /**
   * Cópias defensivas das listas.
   *
   * <p>Sem elas o SpotBugs acusa {@code EI_EXPOSE_REP} e o build quebra — corretamente: um record
   * não congela o CONTEÚDO das coleções que guarda, e quem tivesse a referência da lista original
   * poderia alterar os fatores DEPOIS de o DTO ter sido montado, fazendo a explicação divergir do
   * score que a acompanha.
   */
  public PrevisaoFalhaResponse {
    fatoresPrincipais = List.copyOf(fatoresPrincipais);
    featuresImputadas = List.copyOf(featuresImputadas);
  }

  /**
   * Um fator, já arredondado para leitura.
   *
   * @param contribuicao em log-odds. Somada às demais e ao intercepto, reconstrói o score — é o que
   *     torna a explicação auditável em vez de decorativa.
   * @param pesoRelativo fração do DESVIO em relação à entrega média, <b>não</b> da probabilidade: a
   *     sigmoide não é linear e probabilidade não se decompõe aditivamente.
   */
  @Schema(description = "Contribuição de uma característica para o risco desta entrega")
  public record FatorResponse(
      @Schema(example = "TENTATIVAS_ANTERIORES") String caracteristica,
      @Schema(example = "Tentativas anteriores de entrega") String rotulo,
      @Schema(example = "0.9214") BigDecimal contribuicao,
      @Schema(description = "AUMENTA ou REDUZ o risco", example = "AUMENTA") String direcao,
      @Schema(example = "0.42") BigDecimal pesoRelativo,
      @Schema(example = "2 tentativa(s)") String valorObservado) {

    static FatorResponse de(FatorRisco f) {
      return new FatorResponse(
          f.caracteristica().name(),
          f.rotulo(),
          BigDecimal.valueOf(f.contribuicao()).setScale(4, RoundingMode.HALF_UP),
          f.direcao().name(),
          BigDecimal.valueOf(f.pesoRelativo()).setScale(4, RoundingMode.HALF_UP),
          f.valorObservado());
    }
  }

  public static PrevisaoFalhaResponse de(ResultadoRisco r) {
    return new PrevisaoFalhaResponse(
        r.probabilidadeFalha(),
        r.faixaRisco(),
        r.multiplicadorRecompensa(),
        r.fatoresPrincipais().stream().map(FatorResponse::de).toList(),
        r.featuresImputadas(),
        r.versaoModelo());
  }
}
