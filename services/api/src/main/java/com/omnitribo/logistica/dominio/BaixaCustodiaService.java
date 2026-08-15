package com.omnitribo.logistica.dominio;

import com.omnitribo.logistica.api.BaixaCustodia;
import com.omnitribo.logistica.infra.EntregaFalidaRepository;
import com.omnitribo.logistica.infra.PontoCustodiaRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementação de {@link BaixaCustodia}. Ver o javadoc da porta para o contrato.
 *
 * <p><b>Classe separada de {@code EntregaFalidaService} para quebrar um ciclo de beans</b>, e vale
 * registrar porque a alternativa parece mais natural e não funciona: {@code MissaoService} precisa
 * de {@link BaixaCustodia}, e {@code EntregaFalidaService} precisa de {@code
 * ConversaoEntregaFalida}, que é o próprio {@code MissaoService}. Pôr os dois métodos na mesma
 * classe fecharia o ciclo {@code MissaoService → EntregaFalidaService → MissaoService}, e o Spring
 * abortaria o startup com {@code BeanCurrentlyInCreationException}.
 *
 * <p>A saída comum seria {@code @Lazy} num dos lados. Foi recusada: {@code @Lazy} não desfaz o
 * ciclo, só adia a explosão para a primeira chamada em runtime, e trocaria uma falha de boot —
 * barulhenta e imediata — por uma falha na conclusão de uma missão, que é caminho de valor. Duas
 * classes com dependências acíclicas é a correção; o {@code @Lazy} seria o curativo.
 */
@Service
public class BaixaCustodiaService implements BaixaCustodia {

  private final EntregaFalidaRepository entregaFalidaRepository;
  private final PontoCustodiaRepository pontoCustodiaRepository;

  public BaixaCustodiaService(
      EntregaFalidaRepository entregaFalidaRepository,
      PontoCustodiaRepository pontoCustodiaRepository) {
    this.entregaFalidaRepository = entregaFalidaRepository;
    this.pontoCustodiaRepository = pontoCustodiaRepository;
  }

  /**
   * {@code MANDATORY}: chamar isto fora de uma transação é erro de programação. A baixa só faz
   * sentido junto com o crédito da conclusão, e uma transação própria aqui poderia commitar a
   * liberação da vaga enquanto o crédito da recompensa faz rollback.
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void darBaixa(UUID missaoId, Instant quando) {
    Optional<EntregaFalida> talvez = entregaFalidaRepository.findByMissaoId(missaoId);
    if (talvez.isEmpty()) {
      // Missão de ENTREGA criada à mão por um usuário, sem entrega falida por trás. Caso normal.
      return;
    }

    EntregaFalida entrega = talvez.get();
    if (entrega.saiuDaCustodia()) {
      // Já baixada. Idempotente por construção: sem esta guarda, uma segunda conclusão da mesma
      // missão — que a máquina de estados impede hoje, mas que um caminho futuro poderia
      // introduzir — decrementaria a ocupação duas vezes.
      return;
    }

    // O lock do ponto vem DEPOIS do lock da missão, que a conclusão já segura. É a ordem global
    // do sistema (missao → ponto_custodia → carteira → usuario); inverter reabre o deadlock.
    pontoCustodiaRepository
        .buscarParaAtualizar(entrega.getPontoCustodiaId())
        .ifPresent(PontoCustodia::registrarSaida);

    entrega.darBaixa(quando);
  }
}
