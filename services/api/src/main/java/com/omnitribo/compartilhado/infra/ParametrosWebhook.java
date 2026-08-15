package com.omnitribo.compartilhado.infra;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Calibração da verificação de webhook de transportadora.
 *
 * <p>Record imutável, no molde de {@code ParametrosRecompensa}: propriedade ausente falha no
 * startup em vez de virar zero silencioso. Registrado por {@code @ConfigurationPropertiesScan} em
 * {@code ApiApplication} — não precisa de {@code @Bean}.
 *
 * <p><b>Por que o segredo vive em configuração e não em tabela.</b> Segredo em tabela é segredo em
 * backup, em dump de suporte e em qualquer {@code make psql} — e o banco desta aplicação é lido por
 * dois papéis distintos ({@code omnitribo_app} e o do Flyway), nenhum dos quais precisa conhecer
 * material de autenticação de parceiro. Em configuração ele entra por variável de ambiente, que é
 * onde o resto dos segredos do projeto já vive (ver {@code .env.example} e a regra "nenhum segredo
 * em arquivo versionado" do CLAUDE.md).
 *
 * <p>O custo assumido é que cadastrar transportadora nova exige um deploy. É aceitável: a lista
 * muda em ritmo de contrato comercial, não de operação.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "O mapa devolvido por segredos() já é imutável — normalizar() o constrói com"
            + " toUnmodifiableMap, e o compact constructor troca qualquer entrada mutável por"
            + " essa cópia. Quem chama deve usar segredoDe(slug); o acessor existe porque é um"
            + " record.")
@ConfigurationProperties(prefix = "app.webhooks")
public record ParametrosWebhook(
    /**
     * Segredo HMAC por transportadora, indexado pelo slug que ela envia no cabeçalho de
     * identificação. Mapa VAZIO é estado legítimo: significa "nenhuma transportadora integrada", e
     * todo webhook é recusado com 401 — que é a falha na direção segura.
     */
    Map<String, String> segredos,

    /**
     * Tolerância do carimbo de tempo, para os dois lados. Cinco minutos absorve deriva de relógio
     * entre servidores sem transformar a assinatura capturada em credencial de longa duração: fora
     * dessa janela, um corpo assinado e interceptado deixa de ser reproduzível mesmo com a
     * assinatura correta.
     */
    Duration janelaTimestamp,

    /** Teto próprio de requisições por minuto, por transportadora. */
    int requisicoesPorMinuto) {

  public ParametrosWebhook {
    segredos = segredos == null ? Map.of() : normalizar(segredos);
    if (janelaTimestamp == null || janelaTimestamp.isNegative() || janelaTimestamp.isZero()) {
      throw new IllegalArgumentException("app.webhooks.janela-timestamp deve ser positiva");
    }
    if (requisicoesPorMinuto <= 0) {
      throw new IllegalArgumentException("app.webhooks.requisicoes-por-minuto deve ser positivo");
    }
  }

  /**
   * Slug em minúsculas na indexação.
   *
   * <p>A ligação relaxada do Spring já entrega a chave em minúsculas quando ela vem de variável de
   * ambiente ({@code APP_WEBHOOKS_SEGREDOS_CORREIOS} → {@code correios}), mas quando vem de YAML
   * ela chega como foi escrita. Sem normalizar, a mesma transportadora configurada como {@code
   * Correios} no YAML e enviando {@code correios} no cabeçalho receberia 401 sem nenhuma pista do
   * motivo.
   */
  private static Map<String, String> normalizar(Map<String, String> bruto) {
    return bruto.entrySet().stream()
        .collect(
            Collectors.toUnmodifiableMap(
                e -> e.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue));
  }

  /** Segredo da transportadora, ou vazio se ela não está integrada. */
  public java.util.Optional<String> segredoDe(String slug) {
    if (slug == null || slug.isBlank()) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.ofNullable(segredos.get(slug.toLowerCase(Locale.ROOT)));
  }
}
