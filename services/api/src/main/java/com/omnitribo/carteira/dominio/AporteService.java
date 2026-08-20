package com.omnitribo.carteira.dominio;

import com.omnitribo.carteira.api.AporteToken;
import com.omnitribo.carteira.api.ResultadoAporte;
import com.omnitribo.carteira.infra.CarteiraRepository;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.compartilhado.dominio.RegraNegocioVioladaException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Implementação de {@link AporteToken}. Ver o javadoc da porta para o porquê desta operação. */
@Service
public class AporteService implements AporteToken {

  private static final String CARTEIRA_AUSENTE = "Carteira do patrocinador não encontrada.";

  private final CarteiraRepository carteiraRepository;
  private final LivroRazaoService livroRazaoService;

  public AporteService(CarteiraRepository carteiraRepository, LivroRazaoService livroRazaoService) {
    this.carteiraRepository = carteiraRepository;
    this.livroRazaoService = livroRazaoService;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public ResultadoAporte aportar(
      UUID patrocinadorUsuarioId, long tokens, String chaveIdempotencia, Instant agora) {

    if (tokens <= 0) {
      throw new RegraNegocioVioladaException("Aporte precisa ser de pelo menos 1 token.");
    }

    // 1. LOCK. Projeção escalar primeiro, nunca findByUsuarioId: materializar a Carteira aqui a
    // poria no persistence context e o buscarParaAtualizar seguinte devolveria a instância em cache
    // sem emitir o SELECT ... FOR UPDATE — o lock sumiria em silêncio.
    UUID carteiraId =
        carteiraRepository
            .buscarIdPorUsuario(patrocinadorUsuarioId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));
    Carteira carteira =
        carteiraRepository
            .buscarParaAtualizar(carteiraId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));

    // 2. SONDA, sob o lock. É o que fecha a janela entre sondar e inserir: duas requisições com a
    // mesma chave estão serializadas por esta linha, então a sondagem do perdedor roda depois do
    // commit do vencedor e enxerga a linha dele.
    //
    // Num endpoint que EMITE moeda isto é a defesa principal, não conforto de cliente. Um retry que
    // cunhasse duas vezes aumentaria a oferta sem deixar rastro detectável: a reconciliação
    // continuaria verde, porque ledger e projeção estariam ambos errados na mesma direção.
    Optional<Lancamento> existente = livroRazaoService.consultar(chaveIdempotencia);
    if (existente.isPresent()) {
      return new ResultadoAporte(existente.get().getId(), carteira.getSaldoTokens(), true);
    }

    // 3. ESCREVE. Sem missao_id e sem contraparte: um aporte não pertence a missão nenhuma e não
    // tem
    // outro lado — é exatamente isso que faz dele uma emissão, e é por isso que ele é o único
    // motivo
    // do enum que não soma zero com nada.
    Lancamento lancamento =
        livroRazaoService.registrar(
            carteira,
            Movimento.deTokens(
                SinalLancamento.CREDITO,
                MotivoLancamento.APORTE_PATROCINADOR,
                tokens,
                null,
                null,
                chaveIdempotencia,
                null,
                agora));

    return new ResultadoAporte(lancamento.getId(), carteira.getSaldoTokens(), false);
  }
}
