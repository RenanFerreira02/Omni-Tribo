package com.omnitribo.carteira.dominio;

import com.omnitribo.carteira.api.BeneficioResponse;
import com.omnitribo.carteira.infra.BeneficioRepository;
import com.omnitribo.carteira.infra.ParceiroRepository;
import com.omnitribo.compartilhado.api.ConsultasGeoespaciais;
import com.omnitribo.compartilhado.dominio.RegraNegocioVioladaException;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O catálogo do sumidouro: benefícios ativos de parceiros ativos.
 *
 * <p>Dois recortes mutuamente exclusivos — proximidade ou tribo. Não são combináveis de propósito:
 * "perto de mim E da minha tribo" descreveria dois critérios de pertencimento sobre o mesmo
 * conjunto, e o resultado seria indistinguível do mais restritivo dos dois.
 */
@Service
public class CatalogoBeneficiosService {

  /**
   * Teto de parceiros que a busca por raio considera antes de paginar.
   *
   * <p>A ordenação por distância vem do PostGIS, então a página precisa ser recortada de uma lista
   * JÁ ordenada — e materializar essa lista tem custo. Cinquenta parceiros num raio de bairro é
   * folga larga: a V906 semeia quatro. O teto existe para que um raio absurdo (`raioMetros=999999`)
   * não vire uma varredura da tabela inteira ordenada em memória.
   */
  private static final int TETO_PARCEIROS = 50;

  private final ConsultasGeoespaciais consultasGeoespaciais;
  private final ParceiroRepository parceiroRepository;
  private final BeneficioRepository beneficioRepository;

  public CatalogoBeneficiosService(
      // Injetado pela INTERFACE: é o tipo declarado no campo que o ArchUnit inspeciona, e
      // `compartilhado/infra` é adaptador privado.
      ConsultasGeoespaciais consultasGeoespaciais,
      ParceiroRepository parceiroRepository,
      BeneficioRepository beneficioRepository) {
    this.consultasGeoespaciais = consultasGeoespaciais;
    this.parceiroRepository = parceiroRepository;
    this.beneficioRepository = beneficioRepository;
  }

  /**
   * Catálogo por PROXIMIDADE, do mais perto para o mais longe.
   *
   * <p>A distância vem do PostGIS a cada chamada, por {@code ConsultasGeoespaciais} — nenhum {@code
   * ST_*} mora neste módulo, e nenhuma distância é armazenada. O que volta de lá é {@code
   * AlvoProximo} (id + metros), e é aqui que os benefícios são reidratados.
   */
  @Transactional(readOnly = true)
  public Page<BeneficioResponse> porProximidade(
      BigDecimal lat, BigDecimal lon, int raioMetros, Pageable pagina) {

    List<ConsultasGeoespaciais.AlvoProximo> proximos =
        consultasGeoespaciais.parceirosNoRaio(lat, lon, raioMetros, TETO_PARCEIROS);

    if (proximos.isEmpty()) {
      return new PageImpl<>(List.of(), pagina, 0);
    }

    // LinkedHashMap preserva a ordem que o PostGIS devolveu — é ela que define a ordenação final.
    Map<UUID, Double> distanciaPorParceiro = new LinkedHashMap<>();
    proximos.forEach(alvo -> distanciaPorParceiro.put(alvo.id(), alvo.distanciaM()));

    Map<UUID, Parceiro> parceiros = indexar(distanciaPorParceiro.keySet().stream().toList());

    List<BeneficioResponse> ordenados =
        beneficioRepository.findByParceiroIdInAndAtivoTrue(parceiros.keySet()).stream()
            .map(
                b ->
                    BeneficioResponse.de(
                        b,
                        parceiros.get(b.getParceiroId()),
                        distanciaPorParceiro.get(b.getParceiroId())))
            .sorted(Comparator.comparingDouble(r -> r.distanciaM() == null ? 0 : r.distanciaM()))
            .toList();

    return recortar(ordenados, pagina);
  }

  /** Catálogo por TRIBO. Sem distância: o recorte aqui é de pertencimento, não de geografia. */
  @Transactional(readOnly = true)
  public Page<BeneficioResponse> porTribo(UUID triboId, Pageable pagina) {
    Map<UUID, Parceiro> parceiros =
        parceiroRepository.findByTriboIdAndAtivoTrue(triboId).stream()
            .collect(Collectors.toMap(Parceiro::getId, Function.identity()));

    if (parceiros.isEmpty()) {
      return new PageImpl<>(List.of(), pagina, 0);
    }

    List<BeneficioResponse> beneficios =
        beneficioRepository.findByParceiroIdInAndAtivoTrue(parceiros.keySet()).stream()
            .map(b -> BeneficioResponse.de(b, parceiros.get(b.getParceiroId()), null))
            .sorted(Comparator.comparingLong(BeneficioResponse::custoTokens))
            .toList();

    return recortar(beneficios, pagina);
  }

  /**
   * Recorta a página de uma lista já ordenada.
   *
   * <p>Paginar em memória, e não no banco, é consequência de a ordenação vir do PostGIS num caso e
   * do preço no outro: uma consulta paginada por SQL teria de reproduzir a ordenação por distância
   * dentro do próprio SQL, e aí a regra "todo {@code ST_*} numa classe só" cairia. O teto de {@link
   * #TETO_PARCEIROS} é o que mantém essa lista pequena por construção.
   */
  private static Page<BeneficioResponse> recortar(List<BeneficioResponse> todos, Pageable pagina) {
    int inicio = (int) Math.min(pagina.getOffset(), todos.size());
    int fim = Math.min(inicio + pagina.getPageSize(), todos.size());
    return new PageImpl<>(todos.subList(inicio, fim), pagina, todos.size());
  }

  private Map<UUID, Parceiro> indexar(List<UUID> ids) {
    return parceiroRepository.findByIdInAndAtivoTrue(ids).stream()
        .collect(Collectors.toMap(Parceiro::getId, Function.identity()));
  }

  /**
   * O benefício que pode ser resgatado AGORA: ativo, de parceiro ativo.
   *
   * <p>As duas condições, e não só a primeira: a V906 semeia um benefício ativo num parceiro
   * desligado exatamente porque essa combinação é a que passa despercebida.
   */
  @Transactional(readOnly = true)
  public Beneficio resgatavel(UUID beneficioId) {
    Beneficio beneficio =
        beneficioRepository
            .findById(beneficioId)
            .orElseThrow(
                () ->
                    new com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException(
                        "Benefício não encontrado."));

    if (!beneficio.isAtivo()) {
      throw new RegraNegocioVioladaException("Este benefício não está mais disponível.");
    }

    Parceiro parceiro =
        parceiroRepository
            .findById(beneficio.getParceiroId())
            .orElseThrow(
                () ->
                    new com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException(
                        "Parceiro não encontrado."));

    if (!parceiro.isAtivo()) {
      throw new RegraNegocioVioladaException(
          "O parceiro " + parceiro.getNome() + " não está mais no programa.");
    }

    return beneficio;
  }
}
