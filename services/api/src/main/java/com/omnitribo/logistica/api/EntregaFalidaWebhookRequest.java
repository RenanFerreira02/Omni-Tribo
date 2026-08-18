package com.omnitribo.logistica.api;

import com.omnitribo.logistica.dominio.TipoEndereco;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Corpo do webhook de entrega falida.
 *
 * <p><b>Não tem campo {@code transportadora}.</b> A identidade de quem chama sai do cabeçalho que o
 * {@code HmacWebhookFilter} VERIFICOU e republicou como atributo da requisição — nunca do corpo. É
 * a mesma regra que o CLAUDE.md aplica à identidade do usuário: o corpo é alegação, e aceitar
 * alegação como identidade permitiria que a transportadora A gravasse encomendas em nome da B, com
 * assinatura válida da própria A.
 *
 * <p><b>Peso e volume são obrigatórios</b> porque a missão que nasce daqui é de categoria ENTREGA,
 * e {@code CriacaoMissaoVerificador} exige os dois para essa categoria — é deles que {@code
 * CalculadoraDeRecompensa} deriva a complexidade. São exatamente o dado que só a transportadora
 * tem, e é por isso que a validação vive aqui, na borda, virando 400 com o campo apontado, em vez
 * de virar um NOT NULL que estouraria como 500 lá no INSERT.
 *
 * <p><b>Não tem {@code xpRecompensa} nem {@code tokensRecompensa}</b>, pelo mesmo motivo que {@code
 * CriarMissaoRequest} não tem: a recompensa é calculada pelo servidor e congelada na criação (ADR
 * 0009). {@code valorOfertadoBrl} é insumo do cálculo, não a recompensa.
 */
public record EntregaFalidaWebhookRequest(
    @NotBlank @Size(max = 100) String codigoRastreio,
    @NotBlank @Size(max = 500) String motivo,
    @NotNull UUID pontoCustodiaId,

    /** Vira o título e a descrição da missão. Ex.: "2 caixas de porcelanato 60x60". */
    @NotBlank @Size(min = 5, max = 120) String descricaoDoItem,
    @NotNull @DecimalMin("0.01") @Digits(integer = 4, fraction = 2) BigDecimal pesoKg,
    @NotNull @DecimalMin("0.01") @Digits(integer = 6, fraction = 2) BigDecimal volumeL,

    /**
     * Valor que a transportadora oferece. Opcional, e NUNCA vira {@code missao.valor_brl} — entra
     * como insumo da fórmula de recompensa em TOKEN. Ver {@code ck_missao_economia} e o ADR 0009.
     */
    @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal valorOfertadoBrl,

    /**
     * Endereço ORIGINAL de entrega — o destino da missão.
     *
     * <p>Opcional em par: sem ele a distância não entra no cálculo e toda entrega falida pagaria
     * igual, a da esquina e a do outro lado do bairro. Informar só uma das coordenadas é 400.
     */
    @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal destinoLat,
    @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal destinoLon,
    @NotBlank @Pattern(regexp = "\\d{8}", message = "CEP deve ter 8 dígitos, só números")
        String cep,
    @NotBlank @Size(max = 200) String logradouro,
    @NotBlank @Size(max = 100) String bairro,
    @NotBlank @Size(max = 100) String cidade,
    @NotBlank @Pattern(regexp = "[A-Z]{2}", message = "UF deve ter 2 letras maiúsculas") String uf,

    /**
     * Contexto da tentativa que falhou, para o modelo de previsão de risco.
     *
     * <p><b>Os três são OPCIONAIS, e isso é compatibilidade e não descuido.</b> O webhook já está
     * integrado com transportadoras que enviam o corpo anterior a esta fase; torná-los obrigatórios
     * quebraria essas integrações e faria o pacote ficar preso no ponto de custódia por causa de um
     * campo novo. O que faltar é imputado: hora e dia da semana caem para o instante do
     * recebimento, tipo de endereço para {@code RESIDENCIAL} (a categoria de referência do modelo,
     * contribuição zero), e tentativas para 0 — a leitura conservadora, porque assumir tentativas
     * que não sabemos ter havido inflaria o risco e, com ele, a recompensa.
     */
    @Min(value = 0, message = "Hora deve estar entre 0 e 23")
        @Max(value = 23, message = "Hora deve estar entre 0 e 23")
        Integer janelaHoraInicio,
    TipoEndereco tipoEndereco,
    @Min(value = 0, message = "Tentativas anteriores não pode ser negativo")
        @Max(value = 20, message = "Mais de 20 tentativas indica erro de integração")
        Integer tentativasAnteriores) {

  /** As duas coordenadas andam juntas ou nenhuma vem. */
  public boolean destinoConsistente() {
    return (destinoLat == null) == (destinoLon == null);
  }

  /** {@code RESIDENCIAL} quando ausente: é a referência do modelo, ou seja, contribuição zero. */
  public TipoEndereco tipoEnderecoOuPadrao() {
    return tipoEndereco == null ? TipoEndereco.RESIDENCIAL : tipoEndereco;
  }

  /** Zero quando ausente. Ver o javadoc dos campos para por que a leitura é conservadora. */
  public int tentativasAnterioresOuZero() {
    return tentativasAnteriores == null ? 0 : tentativasAnteriores;
  }
}
