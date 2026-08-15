package com.omnitribo.logistica.treino;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnitribo.logistica.dominio.TipoEndereco;
import java.time.DayOfWeek;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pina o gerador do dataset sintético e confere que as correlações injetadas estão de fato no dado.
 *
 * <p>Se o gerador mudar, este teste falha ANTES de {@code ModeloRiscoTreinoTest} — e o diagnóstico
 * fica óbvio: o problema é o dado, não o treino.
 */
class DatasetSinteticoTest {

  /**
   * Digest canônico do dataset publicado.
   *
   * <p>Pina o gerador <b>mais forte que qualquer estatística agregada</b>: taxa-base e correlações
   * podem continuar batendo depois de uma mudança que só reordenou os sorteios, mas o digest não.
   * Um bit diferente em qualquer uma das 5.000 linhas muda este hexadecimal.
   */
  private static final String DIGEST_ESPERADO =
      "e812d57558fd517bc4956abbe3c44d40c9a3f8bb1cfea13f7269ddf2cfda02e2";

  private static List<AmostraEntrega> dataset;

  @BeforeAll
  static void gerar() {
    dataset = GeradorDatasetEntregas.gerar();
  }

  @Test
  void o_dataset_e_reproduzivel_bit_a_bit() {
    assertThat(GeradorDatasetEntregas.digestSha256(dataset)).isEqualTo(DIGEST_ESPERADO);
  }

  @Test
  void duas_geracoes_com_a_mesma_semente_sao_identicas() {
    assertThat(GeradorDatasetEntregas.digestSha256(GeradorDatasetEntregas.gerar(42L, 500)))
        .isEqualTo(GeradorDatasetEntregas.digestSha256(GeradorDatasetEntregas.gerar(42L, 500)));
  }

  @Test
  void sementes_diferentes_produzem_datasets_diferentes() {
    assertThat(GeradorDatasetEntregas.digestSha256(GeradorDatasetEntregas.gerar(1L, 500)))
        .isNotEqualTo(GeradorDatasetEntregas.digestSha256(GeradorDatasetEntregas.gerar(2L, 500)));
  }

  @Test
  void tem_o_tamanho_publicado() {
    assertThat(dataset).hasSize(GeradorDatasetEntregas.REGISTROS);
  }

  @Test
  void a_taxa_base_de_falha_fica_perto_do_alvo_de_20_por_cento() {
    double taxa = proporcaoDeFalha(dataset);
    // Faixa larga porque a taxa é CONSEQUÊNCIA do intercepto e das distribuições, não um parâmetro
    // ajustado diretamente. Fora dela, a calibração do gerador saiu do lugar.
    assertThat(taxa).isBetween(0.18, 0.30);
  }

  // ────────────────── As correlações injetadas aparecem no dado gerado ──────────────────

  @Test
  void a_janela_noturna_falha_mais_que_a_matinal() {
    double noite =
        proporcaoDeFalha(
            filtrar(a -> a.features().horaDoDia() >= 18 && a.features().horaDoDia() < 22));
    double manha =
        proporcaoDeFalha(
            filtrar(a -> a.features().horaDoDia() >= 6 && a.features().horaDoDia() < 12));
    assertThat(noite).isGreaterThan(manha);
  }

  @Test
  void tentativa_anterior_e_o_preditor_mais_forte() {
    double comTentativa = proporcaoDeFalha(filtrar(a -> a.features().tentativasAnteriores() > 0));
    double primeira = proporcaoDeFalha(filtrar(a -> a.features().tentativasAnteriores() == 0));
    // Injetado como +1,10 por tentativa: de longe o maior efeito isolado do conjunto.
    assertThat(comTentativa).isGreaterThan(primeira * 1.5);
  }

  @Test
  void comercio_falha_muito_mais_no_fim_de_semana_que_em_dia_util() {
    double comercioFimDeSemana =
        proporcaoDeFalha(
            filtrar(
                a ->
                    a.features().tipoEndereco() == TipoEndereco.COMERCIAL
                        && a.features().fimDeSemana()));
    double comercioDiaUtil =
        proporcaoDeFalha(
            filtrar(
                a ->
                    a.features().tipoEndereco() == TipoEndereco.COMERCIAL
                        && !a.features().fimDeSemana()));
    // É a INTERAÇÃO injetada. Se este teste falhar, o termo de produto perdeu o sentido.
    assertThat(comercioFimDeSemana).isGreaterThan(comercioDiaUtil * 1.4);
  }

  @Test
  void residencial_falha_um_pouco_menos_no_fim_de_semana() {
    double fds =
        proporcaoDeFalha(
            filtrar(
                a ->
                    a.features().tipoEndereco() == TipoEndereco.RESIDENCIAL
                        && a.features().fimDeSemana()));
    double util =
        proporcaoDeFalha(
            filtrar(
                a ->
                    a.features().tipoEndereco() == TipoEndereco.RESIDENCIAL
                        && !a.features().fimDeSemana()));
    // Efeito pequeno e de sinal CONTRÁRIO ao do comércio: as pessoas estão em casa.
    assertThat(fds).isLessThan(util);
  }

  @Test
  void cep_de_faixa_ruim_falha_mais_que_cep_de_faixa_boa() {
    double ruim = proporcaoDeFalha(filtrar(a -> a.features().taxaHistoricaCep() >= 0.25));
    double boa = proporcaoDeFalha(filtrar(a -> a.features().taxaHistoricaCep() <= 0.09));
    assertThat(ruim).isGreaterThan(boa * 1.5);
  }

  @Test
  void endereco_rural_falha_mais_que_residencial() {
    double rural =
        proporcaoDeFalha(filtrar(a -> a.features().tipoEndereco() == TipoEndereco.RURAL));
    double residencial =
        proporcaoDeFalha(filtrar(a -> a.features().tipoEndereco() == TipoEndereco.RESIDENCIAL));
    assertThat(rural).isGreaterThan(residencial);
  }

  @Test
  void chuva_forte_falha_mais_que_tempo_seco() {
    double chuva = proporcaoDeFalha(filtrar(a -> a.features().chuvaMm() >= 10.0));
    double seco = proporcaoDeFalha(filtrar(a -> a.features().chuvaMm() == 0.0));
    assertThat(chuva).isGreaterThan(seco);
  }

  // ─────────────────────────── O ruído está lá, e é o que impede 100% ───────────────────────────

  @Test
  void o_erro_de_bayes_e_irredutivel_e_impede_acuracia_perfeita() {
    double teto = AvaliadorModelo.acuraciaMaximaTeorica(dataset);
    // Existe porque o rótulo é SORTEADO de Bernoulli(p), não decidido por regra. Nenhum modelo, nem
    // o que gerou os dados, ultrapassa isto — é a resposta honesta a "por que não 95%?".
    assertThat(teto).isBetween(0.70, 0.90);
  }

  @Test
  void ha_rotulos_invertidos_na_proporcao_declarada() {
    long invertidos = dataset.stream().filter(AmostraEntrega::rotuloInvertido).count();
    double proporcao = (double) invertidos / dataset.size();
    assertThat(proporcao)
        .isCloseTo(
            GeradorDatasetEntregas.PROPORCAO_RUIDO_ROTULO,
            org.assertj.core.data.Offset.offset(0.01));
  }

  @Test
  void a_variavel_omitida_afeta_o_desfecho_e_nao_esta_entre_as_caracteristicas() {
    double comExperiente = proporcaoDeFalha(filtrar(AmostraEntrega::motoristaExperiente));
    double semExperiente = proporcaoDeFalha(filtrar(a -> !a.motoristaExperiente()));
    // Motorista experiente falha MENOS — e o modelo nunca vê esta coluna. É a variável omitida que
    // simula o que sempre acontece na operação: parte do que explica a falha não está registrada.
    assertThat(comExperiente).isLessThan(semExperiente);
  }

  // ─────────────────────────────────── Auxiliares ───────────────────────────────────

  @Test
  void toda_amostra_tem_caracteristicas_dentro_das_faixas_declaradas() {
    for (AmostraEntrega a : dataset) {
      assertThat(a.features().horaDoDia()).isBetween(0, 23);
      assertThat(a.features().diaSemana()).isIn((Object[]) DayOfWeek.values());
      assertThat(a.features().pesoKg()).isBetween(0.1, 30.0);
      assertThat(a.features().volumeL()).isBetween(1.0, 250.0);
      assertThat(a.features().chuvaMm()).isBetween(0.0, 25.0);
      assertThat(a.features().temperaturaC()).isBetween(12.0, 36.0);
      assertThat(a.features().tentativasAnteriores()).isBetween(0, 3);
    }
  }

  private static List<AmostraEntrega> filtrar(java.util.function.Predicate<AmostraEntrega> p) {
    return dataset.stream().filter(p).toList();
  }

  private static double proporcaoDeFalha(List<AmostraEntrega> amostras) {
    assertThat(amostras).as("estrato vazio invalidaria a comparação").isNotEmpty();
    return (double) amostras.stream().filter(AmostraEntrega::falhou).count() / amostras.size();
  }
}
