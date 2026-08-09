package com.omnitribo.integracoes;

import com.omnitribo.integracoes.api.ClimaResponse;
import com.omnitribo.integracoes.api.EnderecoResponse;
import com.omnitribo.integracoes.api.ServicoExternoIndisponivelException;
import com.omnitribo.integracoes.dominio.FonteClima;
import com.omnitribo.integracoes.dominio.FonteEndereco;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Fontes externas dublê, para o teste de contrato HTTP não depender da internet.
 *
 * <p>Classe TOP-LEVEL, e não aninhada no teste, de propósito: o Spring Framework 7.1 deixou de
 * ignorar classes aninhadas anotadas com {@code @Configuration}, e uma delas seria detectada como
 * "default configuration class" — substituindo a descoberta do {@code @SpringBootConfiguration} e
 * subindo um contexto sem a aplicação. O mesmo motivo já documentado em {@code
 * TesteIntegracaoMvcBase}.
 *
 * <p>O comportamento é controlado por coordenada e por CEP, e não por mock com {@code when(...)},
 * para que cada caso do teste se leia como um cenário e não como programação de dublê.
 */
@TestConfiguration
public class FontesExternasFalsasConfig {

  /** Latitude que faz a fonte de clima fingir que o provedor caiu. */
  public static final BigDecimal LAT_PROVEDOR_FORA_DO_AR = new BigDecimal("89.9");

  /** CEP bem formado que o provedor não conhece. */
  public static final String CEP_INEXISTENTE = "99999999";

  /** CEP que faz a fonte fingir que o provedor caiu. */
  public static final String CEP_PROVEDOR_FORA_DO_AR = "00000000";

  @Bean
  @Primary
  public FonteClima fonteClimaFalsa() {
    return (lat, lon) -> {
      if (lat.compareTo(LAT_PROVEDOR_FORA_DO_AR) == 0) {
        throw new ServicoExternoIndisponivelException(
            "Não foi possível consultar a previsão do tempo agora.");
      }
      return new ClimaResponse(
          new BigDecimal("21.3"), new BigDecimal("20.1"), 61, "Chuva", Instant.EPOCH);
    };
  }

  @Bean
  @Primary
  public FonteEndereco fonteEnderecoFalsa() {
    return cep -> {
      if (CEP_PROVEDOR_FORA_DO_AR.equals(cep)) {
        throw new ServicoExternoIndisponivelException("Não foi possível consultar o CEP agora.");
      }
      if (CEP_INEXISTENTE.equals(cep)) {
        return Optional.empty();
      }
      return Optional.of(new EnderecoResponse(cep, "Praça da Sé", "Sé", "São Paulo", "SP"));
    };
  }
}
