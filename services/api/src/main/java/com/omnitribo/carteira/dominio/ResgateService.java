package com.omnitribo.carteira.dominio;

import com.omnitribo.carteira.api.ResgateResponse;
import com.omnitribo.carteira.infra.CarteiraRepository;
import com.omnitribo.carteira.infra.ResgateRepository;
import com.omnitribo.compartilhado.dominio.Auditavel;
import com.omnitribo.compartilhado.dominio.ChaveIdempotencia;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.compartilhado.dominio.RegraNegocioVioladaException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resgate de benefício: o SUMIDOURO do TOKEN.
 *
 * <p>É a operação que fecha a economia como CICLO em vez de estoque. Até aqui o token entrava
 * (aporte do patrocinador, ADR 0024) e circulava (missões), mas nunca saía — a oferta só podia
 * crescer. O resgate QUEIMA: o lançamento debita a carteira e <b>não credita ninguém</b>, sem
 * contraparte e sem missão. Ver ADR 0027.
 *
 * <p><b>Consequência de enunciado, e é o ponto desta fase:</b> {@code SUM(carteiras) + SUM(potes)}
 * deixa de ser constante no SISTEMA e passa a ser constante no CICLO DE MISSÕES. A reconciliação
 * (ledger × projeção) continua valendo intocada — a queima escreve os dois lados —, o que é mais
 * uma demonstração de que ela nunca provou conservação.
 */
@Service
public class ResgateService {

  private static final String CARTEIRA_AUSENTE = "Carteira não encontrada.";

  /**
   * Tentativas de gerar um código não colidente.
   *
   * <p>Com 31 símbolos em 8 posições são ~2,5×10¹² combinações; a chance de colisão num catálogo de
   * bairro é desprezível, e três tentativas cobrem o azar. Não é laço infinito de propósito: se o
   * espaço amostral algum dia encolher a ponto de três sorteios colidirem, isso é defeito de
   * calibração e deve falhar alto, não girar para sempre.
   */
  private static final int TENTATIVAS_CODIGO = 3;

  private final CarteiraRepository carteiraRepository;
  private final ResgateRepository resgateRepository;
  private final LivroRazaoService livroRazaoService;
  private final CatalogoBeneficiosService catalogoBeneficiosService;

  public ResgateService(
      CarteiraRepository carteiraRepository,
      ResgateRepository resgateRepository,
      LivroRazaoService livroRazaoService,
      CatalogoBeneficiosService catalogoBeneficiosService) {
    this.carteiraRepository = carteiraRepository;
    this.resgateRepository = resgateRepository;
    this.livroRazaoService = livroRazaoService;
    this.catalogoBeneficiosService = catalogoBeneficiosService;
  }

  /**
   * Queima tokens em troca de um benefício.
   *
   * <p>Ordem canônica do projeto, sem desvio: <b>trava a carteira → sonda a chave → valida →
   * escreve</b>. É o lock que fecha a corrida entre sondar e inserir, não a sondagem.
   */
  @Auditavel(acao = "BENEFICIO_RESGATADO", entidade = "resgate")
  @Transactional
  public ResgateResponse resgatar(UUID usuarioId, UUID beneficioId, String chaveDoCliente) {
    Instant agora = Instant.now();

    // Fora do lock de propósito: é leitura de catálogo, não de saldo, e prender a carteira durante
    // ela não protegeria nada. Um benefício desativado entre esta linha e o débito produziria um
    // resgate a mais de um item que acabou de sair do ar — desfecho aceitável, e o preço cobrado é
    // o congelado abaixo.
    Beneficio beneficio = catalogoBeneficiosService.resgatavel(beneficioId);

    // Resolve usuário → carteira SEM materializar a entidade: findByUsuarioId poria uma Carteira
    // gerenciada no persistence context, e o buscarParaAtualizar seguinte devolveria a instância em
    // cache sem emitir o SELECT ... FOR UPDATE — o lock sumiria em silêncio.
    UUID carteiraId =
        carteiraRepository
            .buscarIdPorUsuario(usuarioId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));

    // 1. LOCK.
    Carteira carteira =
        carteiraRepository
            .buscarParaAtualizar(carteiraId)
            .orElseThrow(() -> new RecursoNaoEncontradoException(CARTEIRA_AUSENTE));

    // 2. SONDA, sob o lock. Num sumidouro isso importa tanto quanto num emissor: um retry de rede
    // que queimasse duas vezes tiraria da pessoa um saldo que ela gastou uma vez só, e a
    // reconciliação continuaria verde, porque os dois lançamentos são legítimos vistos de perto.
    String chave = ChaveIdempotencia.resgate(usuarioId, chaveDoCliente);
    Optional<Lancamento> jaFeito = livroRazaoService.consultar(chave);
    if (jaFeito.isPresent()) {
      Resgate anterior =
          resgateRepository
              .findById(jaFeito.get().getId())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Lançamento de resgate "
                              + jaFeito.get().getId()
                              + " sem resgate correspondente — os dois são gravados na mesma"
                              + " transação, então isto é corrupção, não corrida."));
      return ResgateResponse.de(anterior, carteira.getSaldoTokens(), true);
    }

    // 3. VALIDA. Recusa ANTES de qualquer escrita: 422 tem de sair sem efeito colateral nenhum.
    long custo = beneficio.getCustoTokens();
    if (carteira.getSaldoTokens() < custo) {
      throw new RegraNegocioVioladaException(
          "Saldo de "
              + carteira.getSaldoTokens()
              + " tokens é insuficiente para resgatar este benefício, que custa "
              + custo
              + ".");
    }

    // 4. ESCREVE.
    Lancamento lancamento =
        livroRazaoService.registrar(
            carteira,
            Movimento.deTokens(
                SinalLancamento.DEBITO,
                MotivoLancamento.RESGATE,
                custo,
                // missaoId e contraparteCarteiraId NULOS: é isto que faz do resgate um sumidouro e
                // não uma transferência. Nenhuma linha credita ninguém.
                null,
                null,
                chave,
                null,
                agora));

    // O id do RESGATE é o do LANÇAMENTO, e isso não é economia de UUID: liga a queima ao seu
    // registro sem uma coluna a mais, e é o que permite à sondagem de replay acima reencontrar o
    // resgate original a partir da chave de idempotência.
    Resgate resgate =
        new Resgate(lancamento.getId(), usuarioId, beneficioId, custo, codigoUnico(), agora);
    resgateRepository.save(resgate);

    return ResgateResponse.de(resgate, carteira.getSaldoTokens(), false);
  }

  /**
   * Baixa manual do parceiro, via ADMIN.
   *
   * <p>Idempotente: dar baixa num resgate já utilizado devolve o mesmo estado em vez de erro — é o
   * retry de quem não recebeu a resposta, e recusá-lo entregaria um erro para algo que deu certo.
   *
   * <p><b>Não existe caminho de volta.</b> Reverter um resgate significaria ressuscitar token já
   * queimado, isto é, emitir moeda fora do aporte do patrocinador — exatamente o que o ADR 0024
   * concentrou num ponto único e auditado.
   */
  @Auditavel(acao = "RESGATE_UTILIZADO", entidade = "resgate")
  @Transactional
  public ResgateResponse darBaixa(UUID resgateId) {
    Resgate resgate =
        resgateRepository
            .findById(resgateId)
            .orElseThrow(() -> new RecursoNaoEncontradoException("Resgate não encontrado."));

    resgate.darBaixa(Instant.now());
    resgateRepository.save(resgate);

    long saldo =
        carteiraRepository
            .buscarIdPorUsuario(resgate.getUsuarioId())
            .flatMap(carteiraRepository::findById)
            .map(Carteira::getSaldoTokens)
            .orElse(0L);

    return ResgateResponse.de(resgate, saldo, false);
  }

  private String codigoUnico() {
    for (int tentativa = 0; tentativa < TENTATIVAS_CODIGO; tentativa++) {
      String candidato = GeradorCodigoRetirada.gerar();
      if (!resgateRepository.existsByCodigoRetirada(candidato)) {
        return candidato;
      }
    }
    throw new IllegalStateException(
        "Não foi possível gerar código de retirada único em "
            + TENTATIVAS_CODIGO
            + " tentativas — o espaço amostral está esgotado ou o gerador quebrou.");
  }
}
