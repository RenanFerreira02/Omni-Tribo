package com.omnitribo.identidade.dominio;

import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.compartilhado.infra.ConsultasGeoespaciais;
import com.omnitribo.identidade.api.TriboResponse;
import com.omnitribo.identidade.infra.TriboRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leitura de tribos.
 *
 * <p>Sem paginação, e é uma decisão consciente: uma tribo é um bairro, e o número delas cresce com
 * a expansão geográfica do produto — dezenas, não milhões. Paginar a lista faria a tela de registro
 * ter "carregar mais" para escolher onde a pessoa mora. Se um dia forem centenas, o que a tela vai
 * querer é BUSCA por nome ou bairro, não paginação — e aí o contrato muda por um motivo real.
 */
@Service
public class TriboService {

  private static final String NAO_ENCONTRADA = "Tribo não encontrada.";

  /** Seis casas decimais ≈ 0,1 m. Mais que isso é ruído de ponto flutuante, não precisão. */
  private static final int CASAS_COORDENADA = 6;

  private final TriboRepository triboRepository;
  private final ConsultasGeoespaciais consultasGeoespaciais;

  public TriboService(
      TriboRepository triboRepository, ConsultasGeoespaciais consultasGeoespaciais) {
    this.triboRepository = triboRepository;
    this.consultasGeoespaciais = consultasGeoespaciais;
  }

  /**
   * Lista SEM o centro geográfico.
   *
   * <p>O centro custa uma consulta PostGIS por tribo, e a lista serve para escolher um nome num
   * seletor — onde nenhum mapa é desenhado. Calcular N centroides para preencher um {@code
   * <select>} seria N+1 puro. Quem precisa do centro pede a tribo individualmente.
   */
  @Transactional(readOnly = true)
  public List<TriboResponse> listar() {
    return triboRepository.findAll().stream()
        .sorted(Comparator.comparing(Tribo::getNome))
        .map(
            tribo ->
                new TriboResponse(tribo.getId(), tribo.getNome(), tribo.getBairro(), null, null))
        .toList();
  }

  @Transactional(readOnly = true)
  public TriboResponse buscar(UUID id) {
    Tribo tribo =
        triboRepository
            .findById(id)
            .orElseThrow(() -> new RecursoNaoEncontradoException(NAO_ENCONTRADA));
    return comCentro(tribo);
  }

  /** Vazio quando o usuário não tem tribo — não é erro, é o estado de quem ainda não escolheu. */
  @Transactional(readOnly = true)
  public Optional<TriboResponse> daUsuario(UUID triboId) {
    if (triboId == null) {
      return Optional.empty();
    }
    return triboRepository.findById(triboId).map(this::comCentro);
  }

  private TriboResponse comCentro(Tribo tribo) {
    Optional<ConsultasGeoespaciais.Centro> centro =
        consultasGeoespaciais.centroDaTribo(tribo.getId());

    return new TriboResponse(
        tribo.getId(),
        tribo.getNome(),
        tribo.getBairro(),
        centro.map(c -> arredondar(c.lat())).orElse(null),
        centro.map(c -> arredondar(c.lon())).orElse(null));
  }

  private static BigDecimal arredondar(double valor) {
    return BigDecimal.valueOf(valor).setScale(CASAS_COORDENADA, RoundingMode.HALF_UP);
  }
}
