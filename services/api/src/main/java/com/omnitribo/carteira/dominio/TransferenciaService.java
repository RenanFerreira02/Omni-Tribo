package com.omnitribo.carteira.dominio;

import com.omnitribo.carteira.api.TransferenciaResponse;
import com.omnitribo.carteira.infra.CarteiraRepository;
import com.omnitribo.carteira.infra.LancamentoRepository;
import com.omnitribo.compartilhado.dominio.Auditavel;
import com.omnitribo.compartilhado.dominio.ChaveIdempotencia;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.compartilhado.dominio.RegraNegocioVioladaException;
import com.omnitribo.identidade.api.ConsultaAfiliacao;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transferência de tokens entre membros da mesma tribo.
 *
 * <p>É a operação que exige mais cuidado do módulo, por dois motivos que se somam: ela toca DUAS
 * linhas de carteira na mesma transação, e as duas pontas podem se inverter entre requisições
 * concorrentes. Ver {@link #ordenarELocar} para a prova de ausência de deadlock.
 */
@Service
public class TransferenciaService {

  private static final String CARTEIRA_AUSENTE = "Carteira não encontrada.";

  private final CarteiraRepository carteiraRepository;
  private final LancamentoRepository lancamentoRepository;
  private final LivroRazaoService livroRazaoService;
  private final ConsultaAfiliacao consultaAfiliacao;
  private final PoliticaCarteira politica;

  public TransferenciaService(
      CarteiraRepository carteiraRepository,
      LancamentoRepository lancamentoRepository,
      LivroRazaoService livroRazaoService,
      ConsultaAfiliacao consultaAfiliacao,
      PoliticaCarteira politica) {
    this.carteiraRepository = carteiraRepository;
    this.lancamentoRepository = lancamentoRepository;
    this.livroRazaoService = livroRazaoService;
    this.consultaAfiliacao = consultaAfiliacao;
    this.politica = politica;
  }

  @Auditavel(acao = "TOKENS_TRANSFERIDOS", entidade = "carteira")
  @Transactional
  public TransferenciaResponse transferir(
      UUID remetenteId, UUID destinatarioId, long tokens, String mensagem, String chaveDoCliente) {

    Instant agora = Instant.now();

    // Regras que não dependem de saldo vêm antes do lock: não faz sentido travar duas linhas para
    // descobrir que o usuário está transferindo para si mesmo.
    if (remetenteId.equals(destinatarioId)) {
      throw new RegraNegocioVioladaException("Não é possível transferir para a própria carteira.");
    }
    if (tokens > politica.transferenciaTetoPorTransacao()) {
      throw new RegraNegocioVioladaException(
          "Transferência de "
              + tokens
              + " tokens excede o teto de "
              + politica.transferenciaTetoPorTransacao()
              + " por transação.");
    }
    // Regra de domínio da moeda comunitária: TOKEN só circula dentro da tribo (ADR 0004). Sem isto,
    // "comunitária" não significaria nada — o token seria uma moeda global com outro nome.
    if (!consultaAfiliacao.mesmaTribo(remetenteId, destinatarioId)) {
      throw new RegraNegocioVioladaException(
          "Tokens só podem ser transferidos entre membros da mesma tribo.");
    }

    UUID carteiraOrigemId =
        carteiraRepository
            .buscarIdPorUsuario(remetenteId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));
    UUID carteiraDestinoId =
        carteiraRepository
            .buscarIdPorUsuario(destinatarioId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));

    ParTravado par = ordenarELocar(carteiraOrigemId, carteiraDestinoId);
    Carteira origem = par.origem();
    Carteira destino = par.destino();

    String chaveEnvio = ChaveIdempotencia.transferenciaEnviada(remetenteId, chaveDoCliente);
    String chaveRecebimento = ChaveIdempotencia.transferenciaRecebida(remetenteId, chaveDoCliente);

    // SONDAR DEPOIS DO LOCK — é o que fecha a corrida entre sondar e inserir. A carteira do
    // remetente está travada em qualquer ordem, então duas requisições com a mesma chave estão
    // serializadas por ela e a segunda enxerga o lançamento da primeira.
    Optional<Lancamento> jaEnviado = livroRazaoService.consultar(chaveEnvio);
    if (jaEnviado.isPresent()) {
      return new TransferenciaResponse(
          jaEnviado.get().getId(), null, origem.getSaldoTokens(), true);
    }

    if (origem.getSaldoTokens() < tokens) {
      // 422 ANTES de qualquer escrita. Nada foi gravado até aqui, então a recusa é literalmente
      // sem efeito colateral — é o que o teste verifica.
      throw new RegraNegocioVioladaException(
          "Saldo de "
              + origem.getSaldoTokens()
              + " tokens é insuficiente para transferir "
              + tokens
              + ".");
    }

    validarTetoDaJanela(origem.getId(), tokens, agora);

    Lancamento saida =
        livroRazaoService.registrar(
            origem,
            Movimento.deTokens(
                SinalLancamento.DEBITO,
                MotivoLancamento.TRANSFERENCIA_ENVIADA,
                tokens,
                null,
                destino.getId(),
                chaveEnvio,
                mensagem,
                agora));

    Lancamento entrada =
        livroRazaoService.registrar(
            destino,
            Movimento.deTokens(
                SinalLancamento.CREDITO,
                MotivoLancamento.TRANSFERENCIA_RECEBIDA,
                tokens,
                null,
                origem.getId(),
                chaveRecebimento,
                mensagem,
                agora));

    return new TransferenciaResponse(
        saida.getId(), entrada.getId(), origem.getSaldoTokens(), false);
  }

  /**
   * Trava as duas carteiras em ORDEM DETERMINÍSTICA CRESCENTE DE id.
   *
   * <p>É esta ordenação, e nada mais, que impede o deadlock A→B / B→A. Sem ela: duas transferências
   * simultâneas em sentidos opostos entre as MESMAS carteiras pediriam os mesmos dois locks em
   * ordens opostas — cada transação seguraria um e esperaria pelo outro, e o PostgreSQL mataria uma
   * com {@code 40P01} depois de {@code deadlock_timeout} (1 s por padrão). O usuário veria 500, e
   * de forma intermitente, que é o pior modo de falhar.
   *
   * <p>Por que ordenar funciona: deadlock exige um CICLO no grafo de espera. Travando sempre do
   * menor id para o maior, uma transação só pode esperar por outra que segura um id ESTRITAMENTE
   * MENOR do que o próximo que ela quer. A espera portanto sempre anda numa direção só, e um ciclo
   * exigiria voltar. Não é probabilidade reduzida — é impossibilidade por construção.
   *
   * <p>Duas notas que parecem bug a quem revisa:
   *
   * <ul>
   *   <li>{@code UUID.compareTo} compara os dois {@code long} internos COM SINAL, e essa ordem NÃO
   *       é a mesma do tipo {@code uuid} do PostgreSQL. É irrelevante: a prova exige UMA ordem
   *       total consistente entre todos os call sites, não concordância com a do banco.
   *   <li>Ordenamos por {@code carteira.id} — a PK da linha efetivamente travada — e não por {@code
   *       usuario_id}, para que a propriedade seja verificável lendo o SQL emitido.
   * </ul>
   *
   * <p>Duas idas ao banco em vez de um {@code where id in (:a, :b) order by id}: nessa forma o nó
   * {@code LockRows} do PostgreSQL fica ACIMA do nó de acesso que o planner escolher, e com bitmap
   * scan ou índice não ordenado a ordem de emissão das linhas — logo a de aquisição dos locks — não
   * é a do {@code ORDER BY}. O round-trip extra compra uma garantia; a query única compra uma
   * suposição sobre o planner.
   */
  private ParTravado ordenarELocar(UUID carteiraOrigemId, UUID carteiraDestinoId) {
    boolean origemPrimeiro = carteiraOrigemId.compareTo(carteiraDestinoId) < 0;
    UUID primeira = origemPrimeiro ? carteiraOrigemId : carteiraDestinoId;
    UUID segunda = origemPrimeiro ? carteiraDestinoId : carteiraOrigemId;

    Carteira a =
        carteiraRepository
            .buscarParaAtualizar(primeira)
            .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));
    Carteira b =
        carteiraRepository
            .buscarParaAtualizar(segunda)
            .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));

    return origemPrimeiro ? new ParTravado(a, b) : new ParTravado(b, a);
  }

  /**
   * Teto acumulado numa janela deslizante.
   *
   * <p>Sozinho, o teto por transação não limita nada: quem controla a conta faz N transferências de
   * valor abaixo do teto. Este é o que fecha isso.
   *
   * <p>A consulta é racy em tese e correta na prática pelo mesmo motivo da verificação de saldo:
   * roda DEPOIS do lock da carteira de origem, então duas transferências do mesmo remetente estão
   * serializadas e a segunda enxerga o lançamento da primeira.
   */
  private void validarTetoDaJanela(UUID carteiraOrigemId, long tokens, Instant agora) {
    Instant inicioDaJanela = agora.minus(politica.transferenciaJanela());
    long jaEnviado =
        lancamentoRepository.somarTransferenciasEnviadasDesde(carteiraOrigemId, inicioDaJanela);

    if (jaEnviado + tokens > politica.transferenciaTetoPorJanela()) {
      throw new RegraNegocioVioladaException(
          "Transferência excede o teto de "
              + politica.transferenciaTetoPorJanela()
              + " tokens por janela de "
              + politica.transferenciaJanela().toHours()
              + "h. Já enviados na janela: "
              + jaEnviado
              + ".");
    }
  }

  /** Par já travado, devolvido nos papéis de negócio para o chamador não reordenar por engano. */
  private record ParTravado(Carteira origem, Carteira destino) {}
}
