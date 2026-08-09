package com.omnitribo.integracoes.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.integracoes.api.ClimaResponse;
import com.omnitribo.integracoes.api.EnderecoResponse;
import com.omnitribo.integracoes.api.ServicoExternoIndisponivelException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * O cache existe para PROTEGER o provedor externo, e é isso que estes testes medem: não que o valor
 * volte certo — o teste do cliente já cobre isso —, mas quantas vezes a fonte foi realmente
 * chamada.
 *
 * <p>Fonte falsa contando invocações, sem Spring e sem rede: o contador é a asserção.
 */
class CacheIntegracoesTest {

  private static final ClimaResponse CLIMA =
      new ClimaResponse(
          new BigDecimal("21.3"), new BigDecimal("20.1"), 0, "Céu limpo", Instant.EPOCH);

  private static final EnderecoResponse ENDERECO =
      new EnderecoResponse("01001000", "Praça da Sé", "Sé", "São Paulo", "SP");

  // ─── Clima ─────────────────────────────────────────────────────────────────────────────────

  @Test
  void segunda_consulta_a_mesma_coordenada_nao_toca_o_provedor() {
    AtomicInteger chamadas = new AtomicInteger();
    ClimaService servico = climaCom(chamadas, (lat, lon) -> CLIMA);

    servico.consultar(new BigDecimal("-23.5629"), new BigDecimal("-46.6996"));
    servico.consultar(new BigDecimal("-23.5629"), new BigDecimal("-46.6996"));

    assertThat(chamadas).hasValue(1);
  }

  /**
   * O arredondamento é o que torna o cache útil de verdade. Sem ele, cada micro-tremida do GPS
   * geraria uma chave nova e TODA leitura viraria chamada externa — o cache existiria e nunca
   * acertaria. 300 m está dentro da resolução da previsão do provedor, então a resposta é a mesma.
   */
  @Test
  void coordenadas_a_poucas_centenas_de_metros_compartilham_a_entrada() {
    AtomicInteger chamadas = new AtomicInteger();
    ClimaService servico = climaCom(chamadas, (lat, lon) -> CLIMA);

    servico.consultar(new BigDecimal("-23.5629"), new BigDecimal("-46.6996"));
    servico.consultar(new BigDecimal("-23.5631"), new BigDecimal("-46.6994"));

    assertThat(chamadas).hasValue(1);
  }

  @Test
  void coordenadas_de_bairros_diferentes_nao_compartilham_a_entrada() {
    AtomicInteger chamadas = new AtomicInteger();
    ClimaService servico = climaCom(chamadas, (lat, lon) -> CLIMA);

    servico.consultar(new BigDecimal("-23.5629"), new BigDecimal("-46.6996"));
    servico.consultar(new BigDecimal("-3.1181"), new BigDecimal("-60.0217"));

    assertThat(chamadas).hasValue(2);
  }

  /**
   * Indisponibilidade NÃO é cacheada. Se fosse, uma falha de dois segundos do provedor se
   * prolongaria pelo TTL inteiro — dez minutos de card ausente por causa de um soluço.
   */
  @Test
  void falha_do_provedor_nao_fica_no_cache() {
    AtomicInteger chamadas = new AtomicInteger();
    ClimaService servico =
        climaCom(
            chamadas,
            (lat, lon) -> {
              if (chamadas.get() == 1) {
                throw new ServicoExternoIndisponivelException("fora do ar");
              }
              return CLIMA;
            });

    assertThatThrownBy(() -> servico.consultar(new BigDecimal("-23.56"), new BigDecimal("-46.69")))
        .isInstanceOf(ServicoExternoIndisponivelException.class);

    assertThat(servico.consultar(new BigDecimal("-23.56"), new BigDecimal("-46.69")))
        .isEqualTo(CLIMA);
    assertThat(chamadas).hasValue(2);
  }

  // ─── CEP ───────────────────────────────────────────────────────────────────────────────────

  @Test
  void mesmo_cep_consulta_o_provedor_uma_vez_so() {
    AtomicInteger chamadas = new AtomicInteger();
    EnderecoService servico = enderecoCom(chamadas, cep -> Optional.of(ENDERECO));

    servico.buscar("01001000");
    servico.buscar("01001000");

    assertThat(chamadas).hasValue(1);
  }

  /**
   * O negativo também entra no cache. Sem isso, o usuário corrigindo um CEP errado dispara uma
   * chamada externa por tecla — e digitar errado é o caso mais frequente num formulário.
   */
  @Test
  void cep_inexistente_vira_404_e_tambem_e_cacheado() {
    AtomicInteger chamadas = new AtomicInteger();
    EnderecoService servico = enderecoCom(chamadas, cep -> Optional.empty());

    assertThatThrownBy(() -> servico.buscar("99999999"))
        .isInstanceOf(RecursoNaoEncontradoException.class);
    assertThatThrownBy(() -> servico.buscar("99999999"))
        .isInstanceOf(RecursoNaoEncontradoException.class);

    assertThat(chamadas).hasValue(1);
  }

  // ─── Apoio ─────────────────────────────────────────────────────────────────────────────────

  private interface Consulta {
    ClimaResponse aplicar(BigDecimal lat, BigDecimal lon);
  }

  private ClimaService climaCom(AtomicInteger contador, Consulta consulta) {
    FonteClima fonte =
        (lat, lon) -> {
          contador.incrementAndGet();
          return consulta.aplicar(lat, lon);
        };
    return new ClimaService(fonte, Duration.ofMinutes(10));
  }

  private EnderecoService enderecoCom(
      AtomicInteger contador, java.util.function.Function<String, Optional<EnderecoResponse>> f) {
    FonteEndereco fonte =
        cep -> {
          contador.incrementAndGet();
          return f.apply(cep);
        };
    return new EnderecoService(fonte);
  }
}
