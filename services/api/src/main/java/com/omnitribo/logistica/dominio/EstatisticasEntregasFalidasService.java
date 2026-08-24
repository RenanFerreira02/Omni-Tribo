package com.omnitribo.logistica.dominio;

import com.omnitribo.logistica.api.EstatisticasEntregasFalidas;
import com.omnitribo.logistica.api.ResumoEntregasFalidas;
import com.omnitribo.logistica.infra.EntregaFalidaRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementação de {@link EstatisticasEntregasFalidas} (ADR 0029).
 *
 * <p>Classe própria, injetando SÓ o repositório. Pendurar estes métodos em {@code
 * EntregaFalidaService} — que já depende de meio mundo — daria ao painel uma aresta para toda
 * dependência daquele serviço, e é assim que ciclo de bean aparece: por conveniência, não por
 * desenho. O {@code CLAUDE.md} registra que este módulo já tem duas classes de serviço separadas
 * exatamente por isso.
 */
@Service
public class EstatisticasEntregasFalidasService implements EstatisticasEntregasFalidas {

  private final EntregaFalidaRepository entregaFalidaRepository;

  public EstatisticasEntregasFalidasService(EntregaFalidaRepository entregaFalidaRepository) {
    this.entregaFalidaRepository = entregaFalidaRepository;
  }

  /**
   * {@code MANDATORY}: só roda dentro da transação de quem chamou.
   *
   * <p>É o que garante que as agregações dos quatro módulos enxerguem O MESMO snapshot — o painel
   * abre uma transação REPEATABLE READ e todas as portas leem dentro dela. Com {@code REQUIRED}
   * esta consulta abriria transação própria se chamada solta, e o painel voltaria a ser quatro
   * fotografias tiradas em momentos diferentes sem que ninguém percebesse.
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public ResumoEntregasFalidas resumo() {
    EntregaFalidaRepository.DesfechosProjecao d = entregaFalidaRepository.contarDesfechos();
    return new ResumoEntregasFalidas(
        d.getRecebidas(),
        d.getConvertidas(),
        d.getPendentes(),
        d.getLotado(),
        d.getSemPatrocinio());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Map<UUID, Instant> recebimentoPorMissao() {
    // LinkedHashMap e não Collectors.toMap: o segundo estoura com NullPointerException se algum
    // valor vier nulo, e a mensagem não diz qual linha. Aqui recebido_em é NOT NULL desde a V6,
    // então o caso não acontece — mas o modo de falhar seria péssimo se um dia acontecesse.
    Map<UUID, Instant> porMissao = new LinkedHashMap<>();
    for (EntregaFalidaRepository.RecebimentoProjecao r :
        entregaFalidaRepository.buscarRecebimentoPorMissao()) {
      porMissao.put(r.getMissaoId(), r.getRecebidoEm());
    }
    return porMissao;
  }
}
