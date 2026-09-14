package com.omnitribo.compartilhado.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnitribo.carteira.api.BeneficioFiltroRequest;
import com.omnitribo.logistica.api.RecusaFiltroRequest;
import com.omnitribo.missoes.api.PotesImobilizadosFiltroRequest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Os defaults de paginação dos quatro filtros de consulta ADMIN, que ninguém exercitava.
 *
 * <p><b>Por que existe.</b> Os quatro records repetem {@code paginaOuPadrao()} e {@code
 * tamanhoOuPadrao()} com corpo idêntico — oito métodos, e o JaCoCo mostrava cinco ramos nunca
 * executados. A cobertura que havia vinha dos testes de endpoint, que sempre passam os parâmetros
 * explicitamente ou testam o caminho de 400; o ramo do {@code null}, que é o da requisição SEM
 * parâmetro nenhum, ficava de fora.
 *
 * <p><b>O que quebraria sem isto.</b> Um default trocado — `0` virando `1`, ou `20` virando `0` —
 * não falharia teste nenhum, e o efeito seria silencioso: a primeira página some da resposta, ou o
 * endpoint devolve página vazia e parece "sem dados". Não há erro, não há 400; só um número errado.
 *
 * <p><b>Unidade, sem Spring.</b> São records puros e as anotações de validação são exercitadas nos
 * testes de endpoint (`paginacaoInvalidaEh400` e irmãos). O que se mede aqui é a ARITMÉTICA do
 * default, e ela não precisa de contexto — subir Testcontainers para conferir um ternário custaria
 * segundos por asserção sem medir nada a mais.
 */
@DisplayName("Defaults de paginação dos filtros de consulta")
class FiltrosDePaginacaoTest {

  /** Página 0 e tamanho 20 — os mesmos quatro pares, para que divergência entre eles apareça. */
  private static final int PAGINA_PADRAO = 0;

  private static final int TAMANHO_PADRAO = 20;

  @Test
  @DisplayName("requisição sem parâmetro nenhum cai nos padrões, nos quatro filtros")
  void semParametroOsQuatroFiltrosUsamOMesmoPadrao() {
    var outbox = new OutboxFiltroRequest(null, null);
    var recusa = new RecusaFiltroRequest(null, null, null);
    var potes = new PotesImobilizadosFiltroRequest(null, null);
    var beneficio = new BeneficioFiltroRequest(null, null, null, null, null, null);

    assertThat(outbox.paginaOuPadrao()).isEqualTo(PAGINA_PADRAO);
    assertThat(recusa.paginaOuPadrao()).isEqualTo(PAGINA_PADRAO);
    assertThat(potes.paginaOuPadrao()).isEqualTo(PAGINA_PADRAO);
    assertThat(beneficio.paginaOuPadrao()).isEqualTo(PAGINA_PADRAO);

    assertThat(outbox.tamanhoOuPadrao()).isEqualTo(TAMANHO_PADRAO);
    assertThat(recusa.tamanhoOuPadrao()).isEqualTo(TAMANHO_PADRAO);
    assertThat(potes.tamanhoOuPadrao()).isEqualTo(TAMANHO_PADRAO);
    assertThat(beneficio.tamanhoOuPadrao()).isEqualTo(TAMANHO_PADRAO);
  }

  @Test
  @DisplayName("valor informado vence o padrão, inclusive nas bordas 0 e 100")
  void valorInformadoVenceOPadrao() {
    // Página 0 explícita é o caso que um `pagina == 0 ? padrão : pagina` quebraria sem que a
    // asserção de default acima percebesse — os dois valores coincidem.
    assertThat(new OutboxFiltroRequest(0, 100).paginaOuPadrao()).isZero();
    assertThat(new OutboxFiltroRequest(0, 100).tamanhoOuPadrao()).isEqualTo(100);

    assertThat(new RecusaFiltroRequest(720, 3, 1).paginaOuPadrao()).isEqualTo(3);
    assertThat(new RecusaFiltroRequest(720, 3, 1).tamanhoOuPadrao()).isEqualTo(1);

    assertThat(new PotesImobilizadosFiltroRequest(7, 50).paginaOuPadrao()).isEqualTo(7);
    assertThat(new PotesImobilizadosFiltroRequest(7, 50).tamanhoOuPadrao()).isEqualTo(50);

    var beneficio = new BeneficioFiltroRequest(null, null, null, null, 2, 5);
    assertThat(beneficio.paginaOuPadrao()).isEqualTo(2);
    assertThat(beneficio.tamanhoOuPadrao()).isEqualTo(5);
  }

  /**
   * A janela do painel de recusas tem default PRÓPRIO, e ele não é 20 nem 0 — é 24 horas, o ciclo
   * da operação de entrega. Um default de horas trocado mudaria o recorte do painel em silêncio: a
   * resposta continuaria 200, com outro conjunto de linhas.
   */
  @Test
  void janelaDeRecusasPadraoEhVinteEQuatroHoras() {
    assertThat(new RecusaFiltroRequest(null, null, null).horasOuPadrao()).isEqualTo(24);
    assertThat(new RecusaFiltroRequest(1, null, null).horasOuPadrao()).isEqualTo(1);
    assertThat(new RecusaFiltroRequest(720, null, null).horasOuPadrao()).isEqualTo(720);
  }

  /**
   * Os dois recortes do catálogo de benefícios, e a regra é <b>exatamente UM</b> — não "um ou
   * nenhum".
   *
   * <p>Quem decide é o controller, com {@code porProximidade() == porTribo()} → 400: quando os dois
   * são falsos a igualdade também vale, então requisição SEM recorte é 400 igual à com os dois. O
   * record expõe os três predicados e não os combina, o que deixa a decisão num lugar só.
   *
   * <p>{@code geoConsistente()} é a asserção que importa mais: coordenada pela metade ({@code ?lat}
   * sem {@code lon}) é erro do cliente, não um recorte parcial. Sem ela, {@code porProximidade()}
   * devolveria falso e a requisição cairia no ramo de tribo com {@code triboId} nulo.
   */
  @Test
  void catalogoDeBeneficiosExigeExatamenteUmRecorte() {
    UUID tribo = UUID.randomUUID();
    var porProximidade =
        new BeneficioFiltroRequest(
            new BigDecimal("-23.56"), new BigDecimal("-46.69"), 1000, null, null, null);
    var porTribo = new BeneficioFiltroRequest(null, null, null, tribo, null, null);
    var osDois =
        new BeneficioFiltroRequest(
            new BigDecimal("-23.56"), new BigDecimal("-46.69"), 1000, tribo, null, null);
    var nenhum = new BeneficioFiltroRequest(null, null, null, null, null, null);

    assertThat(porProximidade.porProximidade()).isTrue();
    assertThat(porProximidade.porTribo()).isFalse();
    assertThat(porTribo.porTribo()).isTrue();
    assertThat(porTribo.porProximidade()).isFalse();

    // A condição literal do controller: igual = 400. Vale para os DOIS e para NENHUM.
    assertThat(osDois.porProximidade() == osDois.porTribo())
        .as("os dois recortes juntos é 400 — nunca os dois, diz a superfície da API")
        .isTrue();
    assertThat(nenhum.porProximidade() == nenhum.porTribo())
        .as("e sem recorte nenhum também é 400: a regra é exatamente UM")
        .isTrue();

    assertThat(nenhum.geoConsistente()).as("sem coordenada nenhuma é consistente").isTrue();
    assertThat(porProximidade.geoConsistente()).as("a tripla completa é consistente").isTrue();
    assertThat(
            new BeneficioFiltroRequest(new BigDecimal("-23.56"), null, null, null, null, null)
                .geoConsistente())
        .as("lat sem lon é erro do cliente, não recorte parcial")
        .isFalse();
    assertThat(
            new BeneficioFiltroRequest(
                    new BigDecimal("-23.56"), new BigDecimal("-46.69"), null, null, null, null)
                .geoConsistente())
        .as("coordenada sem raio também é meia entrada")
        .isFalse();
  }
}
