package com.omnitribo.compartilhado.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PORTA de consulta geoespacial: o único caminho pelo qual um módulo alcança álgebra de
 * coordenadas.
 *
 * <p>A implementação — e portanto todo {@code ST_*} do sistema — vive em {@code
 * compartilhado/infra/ConsultasGeoespaciaisPostgis}, num arquivo só. Trocar PostGIS por Oracle
 * Spatial é reescrever aquele arquivo e mais nada: {@code ST_DWithin} → {@code
 * SDO_WITHIN_DISTANCE}, {@code ST_Distance} → {@code SDO_GEOM.SDO_DISTANCE}. Substitui a regra "um
 * repositório geo por módulo" do ADR 0002, que espalharia as chamadas por dois arquivos.
 *
 * <p><b>Por que a interface está em {@code api/} e não em {@code infra/}, onde a classe morava.</b>
 * Quatro domínios de módulos diferentes dependem disto — {@code missoes}, {@code geolocalizacao},
 * {@code identidade} e {@code logistica}. Com a classe em {@code infra}, cada um deles importava um
 * ADAPTADOR de outro módulo, que é a única direção que o resto do projeto trata como proibida, e o
 * ArchUnit não pegava porque {@code compartilhado} estava fora da regra. Agora eles dependem de uma
 * porta, como já faziam com {@code PublicadorEventos} e {@code DadosPessoaisDoUsuario}. Emenda ao
 * ADR 0007: a decisão de centralizar não muda, muda o pacote da porta.
 *
 * <p><b>A restrição que dá forma a esta assinatura continua valendo.</b> A regra do ArchUnit é
 * DIRECIONAL: {@code compartilhado} é isento como ALVO, mas suas classes continuam sendo ORIGEM, e
 * mover para {@code api/} não afrouxa isso. Esta interface NÃO pode importar {@code Missao}, {@code
 * StatusMissao} nem {@code CategoriaMissao}. Por isso status e categoria entram como String —
 * sempre {@code .name()} de um enum já validado pelo binder, nunca texto livre do cliente — e o
 * retorno é {@link AlvoProximo}, um par neutro id+distância que o chamador reidrata.
 */
public interface ConsultasGeoespaciais {

  /** Par neutro: quem, e a que distância em metros. Sem tipo de módulo algum. */
  record AlvoProximo(UUID id, double distanciaM) {}

  /**
   * Coordenada nua, pelo mesmo motivo de {@link AlvoProximo}: nenhum tipo de módulo cruza daqui.
   */
  record Centro(double lat, double lon) {}

  /**
   * Missões no raio, da mais próxima para a mais distante.
   *
   * <p>{@code status} e {@code categoria} são String pela restrição de fronteira acima; {@code
   * categoria} nula significa "todas". O raio é em METROS e a distância volta em METROS — nenhuma
   * conversão de unidade acontece em Java.
   */
  List<AlvoProximo> missoesNoRaio(
      BigDecimal lat, BigDecimal lon, int raioMetros, String status, String categoria, int limite);

  /** Pontos de custódia ATIVOS no raio, do mais próximo para o mais distante. */
  List<AlvoProximo> pontosCustodiaNoRaio(
      BigDecimal lat, BigDecimal lon, int raioMetros, int limite);

  /**
   * Centro geográfico DERIVADO de uma tribo. Vazio quando ela ainda não tem missão nem ponto de
   * custódia — inventar um centro seria pior do que o chamador cair no default configurado.
   */
  Optional<Centro> centroDaTribo(UUID triboId);

  /**
   * Distância esférica em metros entre dois pares lat/lon.
   *
   * <p>Recebe quatro escalares e não lê tabela nenhuma — de propósito, não por preguiça. Quem chama
   * já segura {@code SELECT ... FOR UPDATE} sobre a linha da missão e já tem a origem carregada;
   * reler {@code missao} aqui seria uma segunda leitura da mesma linha travada, feita por uma
   * classe de {@code compartilhado} que não pode conhecer a tabela de nenhum módulo.
   */
  double distanciaMetros(BigDecimal latA, BigDecimal lonA, BigDecimal latB, BigDecimal lonB);
}
