package com.omnitribo.logistica.dominio;

import com.omnitribo.compartilhado.api.ConsultasGeoespaciais;
import com.omnitribo.compartilhado.dominio.Coordenadas;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.logistica.api.PontoCustodiaResponse;
import com.omnitribo.logistica.infra.PontoCustodiaRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leitura de pontos de custódia.
 *
 * <p>Só leitura, e é importante saber o que isso significa hoje: <b>nada movimenta {@code ocupacao}
 * ainda.</b> O fluxo de entrega que a incrementaria é da F8 e não existe — a coluna vem do seed e
 * fica parada. A entidade também não tem mais {@code incrementarOcupacao}: mantê-la sugeria um
 * caminho de escrita que nunca foi ligado.
 *
 * <p>Quando esse fluxo chegar, ele NÃO deve virar endpoint: expor escrita aqui deixaria qualquer
 * usuário autenticado marcar uma loja de terceiro como lotada.
 */
@Service
public class PontoCustodiaService {

  private static final String NAO_ENCONTRADO = "Ponto de custódia não encontrado.";
  private static final int CASAS_COORDENADA = 6;

  private final PontoCustodiaRepository pontoCustodiaRepository;
  private final ConsultasGeoespaciais consultasGeoespaciais;

  public PontoCustodiaService(
      PontoCustodiaRepository pontoCustodiaRepository,
      ConsultasGeoespaciais consultasGeoespaciais) {
    this.pontoCustodiaRepository = pontoCustodiaRepository;
    this.consultasGeoespaciais = consultasGeoespaciais;
  }

  /**
   * Ponto INATIVO responde 404, e não um corpo com {@code ativo: false}.
   *
   * <p>Uma missão antiga pode apontar para um ponto desativado, e devolvê-lo levaria o executor a
   * uma loja que não recebe mais encomenda. Some da consulta em vez de aparecer com uma flag que a
   * tela pode esquecer de ler.
   */
  @Transactional(readOnly = true)
  public PontoCustodiaResponse buscar(UUID id) {
    PontoCustodia ponto =
        pontoCustodiaRepository
            .findById(id)
            .filter(PontoCustodia::isAtivo)
            .orElseThrow(() -> new RecursoNaoEncontradoException(NAO_ENCONTRADO));
    return responseDe(ponto, null);
  }

  /**
   * Pontos ativos no raio, do mais próximo para o mais distante.
   *
   * <p>O PostGIS devolve pares (id, distância) — a rehidratação acontece aqui, com um único {@code
   * findAllById}, e a ORDEM vem do banco, não de uma reordenação em Java. Ver ADR 0007: {@code
   * ConsultasGeoespaciais} não pode conhecer os tipos deste módulo, então ela devolve o par neutro
   * e quem sabe o que é um ponto de custódia monta a resposta.
   */
  @Transactional(readOnly = true)
  public List<PontoCustodiaResponse> proximos(
      BigDecimal lat, BigDecimal lon, int raioMetros, int limite) {

    List<ConsultasGeoespaciais.AlvoProximo> alvos =
        consultasGeoespaciais.pontosCustodiaNoRaio(lat, lon, raioMetros, limite);
    if (alvos.isEmpty()) {
      return List.of();
    }

    Map<UUID, Double> distanciaPorId = new LinkedHashMap<>();
    alvos.forEach(alvo -> distanciaPorId.put(alvo.id(), alvo.distanciaM()));

    return pontoCustodiaRepository.findAllById(distanciaPorId.keySet()).stream()
        // findAllById não promete ordem; a que importa é a do PostGIS, por distância crescente.
        .sorted(Comparator.comparingDouble(p -> distanciaPorId.get(p.getId())))
        .map(p -> responseDe(p, arredondar(distanciaPorId.get(p.getId()), 2)))
        .toList();
  }

  private static PontoCustodiaResponse responseDe(PontoCustodia ponto, BigDecimal distanciaM) {
    return new PontoCustodiaResponse(
        ponto.getId(),
        ponto.getCodigo(),
        ponto.getTipo().name(),
        ponto.getApelido(),
        arredondar(Coordenadas.latitude(ponto.getPonto()).doubleValue(), CASAS_COORDENADA),
        arredondar(Coordenadas.longitude(ponto.getPonto()).doubleValue(), CASAS_COORDENADA),
        ponto.getCapacidade(),
        ponto.getOcupacao(),
        distanciaM);
  }

  private static BigDecimal arredondar(double valor, int casas) {
    return BigDecimal.valueOf(valor).setScale(casas, RoundingMode.HALF_UP);
  }
}
