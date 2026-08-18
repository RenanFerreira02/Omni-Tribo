package com.omnitribo.logistica.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnitribo.missoes.dominio.ParametrosRecompensa;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * O teto do multiplicador de risco existe em DOIS blocos de configuração, e eles precisam
 * concordar.
 *
 * <p><b>A duplicação é deliberada, e este teste é o preço dela.</b> Em {@code app.logistica.risco}
 * o teto define como o MODELO mapeia probabilidade em multiplicador; em {@code
 * app.missoes.recompensa} define quanto a economia se dispõe a CUNHAR. São decisões de donos
 * diferentes — recalibrar o modelo não deveria conseguir, sozinho, ampliar a emissão de token.
 *
 * <p>Sem esta trava, a divergência seria SILENCIOSA e assimétrica:
 *
 * <ul>
 *   <li>teto da economia MENOR que o do modelo → a calibração do modelo mentiria sobre o que se
 *       paga: o previsor devolveria 1,80 na resposta do endpoint, a calculadora limitaria a 1,50, e
 *       o número exibido ao usuário nunca seria o creditado;
 *   <li>teto da economia MAIOR → a folga jamais seria usada, mas alguém lendo o YAML concluiria que
 *       a emissão pode chegar a um patamar que o modelo nunca produz.
 * </ul>
 *
 * <p>Lê o {@code application.yml} de produção pelo binder, sem subir contexto Spring — o arquivo
 * real, não uma cópia de teste.
 */
class CoerenciaTetoRiscoTest {

  @Test
  void o_teto_da_economia_concorda_com_o_do_modelo() {
    ParametrosRisco modelo = ligar("app.logistica.risco", ParametrosRisco.class);
    ParametrosRecompensa economia = ligar("app.missoes.recompensa", ParametrosRecompensa.class);

    assertThat(economia.multiplicadorRiscoMaximo())
        .as(
            "app.missoes.recompensa.multiplicador-risco-maximo vs"
                + " app.logistica.risco.multiplicador-maximo")
        .isEqualByComparingTo(BigDecimal.valueOf(modelo.multiplicadorMaximo()));

    assertThat(economia.multiplicadorRiscoMinimo())
        .isEqualByComparingTo(BigDecimal.valueOf(modelo.multiplicadorMinimo()));
  }

  @Test
  void o_piso_e_um_nos_dois_blocos() {
    // Risco NUNCA reduz recompensa. Piso abaixo de 1 em qualquer um dos dois lados inverteria a
    // tese do produto: entrega difícil pagaria menos que entrega fácil.
    assertThat(ligar("app.logistica.risco", ParametrosRisco.class).multiplicadorMinimo())
        .isGreaterThanOrEqualTo(1.0);
    assertThat(
            ligar("app.missoes.recompensa", ParametrosRecompensa.class).multiplicadorRiscoMinimo())
        .isGreaterThanOrEqualTo(BigDecimal.ONE);
  }

  private static <T> T ligar(String prefixo, Class<T> tipo) {
    StandardEnvironment ambiente = new StandardEnvironment();
    try {
      new YamlPropertySourceLoader()
          .load("application.yml", new ClassPathResource("application.yml"))
          .forEach(fonte -> ambiente.getPropertySources().addLast(fonte));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Não foi possível ler application.yml do classpath", e);
    }
    return Binder.get(ambiente)
        .bind(prefixo, tipo)
        .orElseThrow(() -> new IllegalStateException(prefixo + " ausente do application.yml"));
  }
}
