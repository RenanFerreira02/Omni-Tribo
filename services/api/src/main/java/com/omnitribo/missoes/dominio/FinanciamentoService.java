package com.omnitribo.missoes.dominio;

import com.omnitribo.carteira.api.FinanciamentoMissao;
import com.omnitribo.carteira.api.ResultadoFinanciamento;
import com.omnitribo.compartilhado.dominio.Auditavel;
import com.omnitribo.compartilhado.dominio.ChaveIdempotencia;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.compartilhado.dominio.RegraNegocioVioladaException;
import com.omnitribo.identidade.api.ConsultaAfiliacao;
import com.omnitribo.missoes.api.FinanciamentoResponse;
import com.omnitribo.missoes.infra.MissaoRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Financiamento de missão comunitária: um membro debita a própria carteira e credita o pote. É o
 * que faz o token que o executor recebe ser o mesmo que um vizinho pôs no pote — a soma (carteiras
 * + potes) não muda no ciclo.
 */
@Service
public class FinanciamentoService {

  private static final String NAO_ENCONTRADA = "Missão não encontrada.";

  private final MissaoRepository missaoRepository;
  private final FinanciamentoMissao financiamentoMissao;
  private final ConsultaAfiliacao consultaAfiliacao;

  public FinanciamentoService(
      MissaoRepository missaoRepository,
      FinanciamentoMissao financiamentoMissao,
      ConsultaAfiliacao consultaAfiliacao) {
    this.missaoRepository = missaoRepository;
    this.financiamentoMissao = financiamentoMissao;
    this.consultaAfiliacao = consultaAfiliacao;
  }

  @Auditavel(acao = "MISSAO_FINANCIADA", entidade = "missao")
  @Transactional
  public FinanciamentoResponse financiar(
      UUID triboId, UUID missaoId, long tokens, UUID financiadorId, String chaveDoCliente) {

    Instant agora = Instant.now();

    // LOCK da missão primeiro (ordem global missao → carteira): financiar e concluir disputam as
    // duas.
    Missao missao =
        missaoRepository
            .buscarParaAtualizar(missaoId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(NAO_ENCONTRADA));

    String chave = ChaveIdempotencia.financiamento(financiadorId, missaoId, chaveDoCliente);

    // Autorização antes da sondagem: senão pote e recompensa vazariam a quem não é da tribo.
    validarAutorizacao(triboId, missao, financiadorId);

    // LOCK (carteira) → SONDA → VALIDA → ESCREVE: sondar depois das regras faria o retry de um
    // financiamento que completou o pote receber 422 em vez do replay.
    Optional<ResultadoFinanciamento> replay = financiamentoMissao.sondar(financiadorId, chave);
    if (replay.isPresent()) {
      return new FinanciamentoResponse(
          missao.getId(),
          missao.getPoteTokens(),
          missao.getTokensRecompensa(),
          replay.get().saldoTokensRestante(),
          true);
    }

    // Regras de estado depois da sondagem: um replay não é uma operação nova.
    validarEstado(missao);
    validarTeto(missao, tokens);

    ResultadoFinanciamento debito =
        financiamentoMissao.debitar(financiadorId, missaoId, tokens, chave, agora);

    // Credita o pote só se o débito aconteceu: num replay o pote já recebeu, e creditar de novo
    // duplicaria tokens.
    if (!debito.replay()) {
      missao.creditarPote(tokens);
      missaoRepository.save(missao);
    }

    return new FinanciamentoResponse(
        missao.getId(),
        missao.getPoteTokens(),
        missao.getTokensRecompensa(),
        debito.saldoTokensRestante(),
        debito.replay());
  }

  /**
   * Quem pode financiar — roda ANTES da sondagem (403 → sondagem → 409/422, como no check-in). O
   * {@code triboId} do path precisa bater com a tribo real do financiador. <b>O criador não
   * financia a própria missão</b> (ADR 0037): "quem cria não paga" (ADR 0009) passou a ser imposto
   * aqui.
   */
  private void validarAutorizacao(UUID triboId, Missao missao, UUID financiadorId) {
    Optional<UUID> triboFinanciador = consultaAfiliacao.triboDe(financiadorId);
    if (triboFinanciador.isEmpty() || !triboFinanciador.get().equals(triboId)) {
      throw new RegraNegocioVioladaException("Você não pertence a esta tribo.");
    }

    // Antes da sondagem com segurança: nunca houve autofinanciamento legítimo a reproduzir.
    if (financiadorId.equals(missao.getCriadorId())) {
      throw new RegraNegocioVioladaException(
          "Quem cria a missão não paga por ela. O pote é formado por outros membros da tribo.");
    }

    if (!consultaAfiliacao.mesmaTribo(financiadorId, missao.getCriadorId())) {
      throw new RegraNegocioVioladaException(
          "Missão pertence a outra tribo e não pode ser financiada por você.");
    }
  }

  /**
   * Se a missão aceita financiamento AGORA — depois da sondagem, porque replay não é operação nova.
   */
  private static void validarEstado(Missao missao) {
    // Testa a FONTE, e não a categoria, para não divergir de `Missao.fontePote`: uma lista de
    // categorias aqui deixaria a ENTREGA (ADR 0038) impublicável e infinanciável ao mesmo tempo.
    // CUNHAGEM só existe como legado — a conclusão dela emite, e um pote por cima ficaria preso.
    if (missao.getFontePote() == FontePote.CUNHAGEM) {
      throw new RegraNegocioVioladaException(
          "Esta missão foi publicada antes do financiamento comunitário de entregas e não aceita"
              + " financiamento em tokens.");
    }

    // PATROCINADOR: a missão de retirada já nasce com o pote completo, financiado dentro da própria
    // conversão do webhook.
    if (missao.getFontePote() == FontePote.PATROCINADOR) {
      throw new RegraNegocioVioladaException(
          "Missão de retirada é financiada pelo patrocinador da transportadora.");
    }

    // Só XP não tem pote (ADR 0035): financiar prenderia o token numa missão que nunca o devolve, e
    // a
    // reconciliação não veria. Quem quer token edita o RASCUNHO (ADR 0036).
    if (missao.getFontePote() == FontePote.SEM_TOKEN) {
      throw new RegraNegocioVioladaException(
          "Esta missão recompensa só em XP e não aceita financiamento. Para recompensar em tokens,"
              + " edite o rascunho antes de publicar.");
    }

    // Terminal: o pote já não paga ninguém.
    if (missao.getStatus().ehTerminal()) {
      throw new RegraNegocioVioladaException("Missão em estado terminal não aceita financiamento.");
    }

    // Rascunho É financiável, por qualquer membro: a publicação exige o pote antes. O risco de
    // token
    // preso fecha com RASCUNHO --CANCELAR--> CANCELADA, que estorna.
  }

  /**
   * O pote não pode passar da recompensa: a conclusão debita exatamente a recompensa e CONCLUIDA é
   * terminal, então a sobra ficaria presa. Recusar na entrada avisa o financiador na hora.
   */
  private static void validarTeto(Missao missao, long tokens) {
    long depois = missao.getPoteTokens() + tokens;
    if (depois > missao.getTokensRecompensa()) {
      throw new RegraNegocioVioladaException(
          "Pote ficaria com "
              + depois
              + " tokens, acima da recompensa de "
              + missao.getTokensRecompensa()
              + ". Faltam apenas "
              + (missao.getTokensRecompensa() - missao.getPoteTokens())
              + ".");
    }
  }
}
