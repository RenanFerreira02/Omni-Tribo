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
 * Financiamento de missão comunitária: membro debita tokens da própria carteira e credita o pote da
 * missão.
 *
 * <p>É o SUMIDOURO da moeda, e é o que torna a economia não-fictícia. Sem ele, concluir uma missão
 * TRIBO cunharia tokens do nada e a oferta cresceria sem limite. Com ele, o token que o executor
 * recebe é exatamente o token que um membro da tribo pôs no pote — a soma (carteiras + potes) é
 * constante ao longo de todo o ciclo de vida da missão.
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

    // LOCK PRIMEIRO, e a missão é a primeira da ordem global: missao → carteira. Financiar e
    // concluir disputam exatamente estas duas linhas; se o financiamento travasse a carteira antes,
    // as duas operações na mesma missão poderiam se travar mutuamente.
    Missao missao =
        missaoRepository
            .buscarParaAtualizar(missaoId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(NAO_ENCONTRADA));

    String chave = ChaveIdempotencia.financiamento(financiadorId, missaoId, chaveDoCliente);

    // Autorização e regras ANTES de escrever. A ordem entre elas importa: o financiador precisa
    // estar na mesma tribo do criador da missão, e checar isso depois do débito exigiria desfazer.
    validarEscopo(triboId, missao, financiadorId);
    validarTeto(missao, tokens);

    ResultadoFinanciamento debito =
        financiamentoMissao.debitar(financiadorId, missaoId, tokens, chave, agora);

    // Só credita o pote se o débito de fato aconteceu. Num replay, o pote já recebeu na primeira
    // chamada — creditar de novo duplicaria tokens que saíram da carteira uma vez só, e a
    // conservação quebraria exatamente no caminho que ela existe para proteger.
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
   * Só membro da tribo financia, e só missão comunitária recebe financiamento.
   *
   * <p>O {@code triboId} do path não é decorativo: ele é o escopo declarado pelo cliente, e
   * conferir que ele bate com a tribo real do financiador impede que alguém financie por uma tribo
   * à qual não pertence só trocando a URL.
   */
  private void validarEscopo(UUID triboId, Missao missao, UUID financiadorId) {
    if (missao.getCategoria() != CategoriaMissao.TRIBO
        && missao.getCategoria() != CategoriaMissao.COLETA) {
      throw new RegraNegocioVioladaException(
          "Só missões TRIBO e COLETA aceitam financiamento em tokens.");
    }

    // Financiar depois de concluída, cancelada ou expirada seria pôr token num pote que já não
    // paga ninguém — no melhor caso vira estorno, no pior fica preso.
    if (missao.getStatus().ehTerminal()) {
      throw new RegraNegocioVioladaException("Missão em estado terminal não aceita financiamento.");
    }

    // Rascunho É financiável, e por qualquer membro da tribo — não é descuido. Publicar missão
    // comunitária exige pote cobrindo a recompensa, então o financiamento acontece necessariamente
    // ANTES da publicação, e restringi-lo ao criador obrigaria uma pessoa só a bancar 100% da
    // missão, matando o co-financiamento que é o propósito da moeda comunitária.
    //
    // O que fecha o risco de token preso não é proibir aqui, é a transição RASCUNHO --CANCELAR-->
    // CANCELADA, que dá ao criador uma saída que estorna o pote (ver StatusMissao).

    Optional<UUID> triboFinanciador = consultaAfiliacao.triboDe(financiadorId);
    if (triboFinanciador.isEmpty() || !triboFinanciador.get().equals(triboId)) {
      throw new RegraNegocioVioladaException("Você não pertence a esta tribo.");
    }

    if (!consultaAfiliacao.mesmaTribo(financiadorId, missao.getCriadorId())) {
      throw new RegraNegocioVioladaException(
          "Missão pertence a outra tribo e não pode ser financiada por você.");
    }
  }

  /**
   * O pote não pode passar da recompensa.
   *
   * <p>A conclusão debita exatamente {@code tokensRecompensa} do pote, e {@code CONCLUIDA} é
   * terminal — sobra no pote ficaria presa para sempre, porque o estorno só roda em CANCELADA e
   * EXPIRADA. Recusar o excedente na entrada é mais simples e mais honesto que estornar resíduo na
   * saída: o financiador descobre na hora que aquele token não é necessário, em vez de descobrir
   * depois que ele sumiu.
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
