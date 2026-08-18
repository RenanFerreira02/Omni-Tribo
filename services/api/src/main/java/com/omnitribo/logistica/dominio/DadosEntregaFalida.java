package com.omnitribo.logistica.dominio;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.UUID;
import org.locationtech.jts.geom.Point;

/**
 * Comando de registro de entrega falida, já validado e com as coordenadas convertidas.
 *
 * <p>Separa o DTO da borda HTTP da entidade: o controller monta este record a partir do corpo do
 * webhook, e a entidade só conhece o comando. Sem ele, o construtor de {@link EntregaFalida} teria
 * quinze parâmetros posicionais, sendo três {@code BigDecimal} e cinco {@code String} — a mesma
 * classe de armadilha que fez {@code Missao} receber a recompensa como objeto e não como dois
 * {@code int} soltos.
 *
 * @param destino coordenada do endereço ORIGINAL de entrega, para onde o executor leva o pacote.
 *     Nulável: é o que faz o adicional de distância ser zero quando a transportadora não informa.
 * @param janelaHoraInicio hora pretendida da entrega que falhou, 0–23. Nulável: transportadora já
 *     integrada não envia, e o modelo de risco cai para a hora derivada de {@code recebido_em}.
 * @param tipoEndereco natureza do destino. Nulável; ausente é tratado como {@code RESIDENCIAL}, a
 *     categoria de referência do modelo — ou seja, contribuição zero em vez de chute.
 * @param tentativasAnteriores quantas vezes a transportadora já tentou. Nulável; ausente vira 0,
 *     que é a leitura conservadora: assumir tentativas que não sabemos ter havido inflaria o risco.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "O acessor destino() devolve um Point de JTS, que é imutável depois de construído — o"
            + " GeometryFactory não permite alterar coordenada de um Point existente. Cópia"
            + " defensiva aqui custaria uma alocação por leitura sem proteger nada.")
public record DadosEntregaFalida(
    String transportadora,
    String codigoRastreio,
    String motivo,
    UUID pontoCustodiaId,
    String descricaoDoItem,
    BigDecimal pesoKg,
    BigDecimal volumeL,
    BigDecimal valorOfertadoBrl,
    Point destino,
    String cep,
    String logradouro,
    String bairro,
    String cidade,
    String uf,
    Short janelaHoraInicio,
    TipoEndereco tipoEndereco,
    Short tentativasAnteriores) {}
