package com.omnitribo.identidade.dominio;

import com.omnitribo.carteira.api.AporteToken;
import com.omnitribo.carteira.api.ProvisionamentoCarteira;
import com.omnitribo.carteira.api.ResultadoAporte;
import com.omnitribo.compartilhado.dominio.Auditavel;
import com.omnitribo.compartilhado.dominio.ChaveIdempotencia;
import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.compartilhado.dominio.RegraNegocioVioladaException;
import com.omnitribo.identidade.api.AporteResponse;
import com.omnitribo.identidade.api.ConsultaPatrocinador;
import com.omnitribo.identidade.api.PatrocinadorResponse;
import com.omnitribo.identidade.infra.PatrocinadorRepository;
import com.omnitribo.identidade.infra.UsuarioRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cadastro e consulta de patrocinadores.
 *
 * <p>É o lado de identidade da carteira de patrocinador (ADR 0024). Aqui nasce o TITULAR; quem move
 * token é {@code carteira}, e quem decide se uma missão é patrocinada é {@code missoes}.
 *
 * <p><b>Fora de escopo, e continua fora:</b> validação de CNPJ, meio de pagamento e prevenção a
 * lavagem. O cadastro é por endpoint ADMIN justamente porque onboarding financeiro de verdade é
 * produto regulado, não MVP acadêmico — ver a seção "Fora de escopo, decidido" do CLAUDE.md.
 */
@Service
public class PatrocinadorService implements ConsultaPatrocinador {

  private final PatrocinadorRepository patrocinadorRepository;
  private final UsuarioRepository usuarioRepository;
  private final ProvisionamentoCarteira provisionamentoCarteira;
  private final AporteToken aporteToken;

  public PatrocinadorService(
      PatrocinadorRepository patrocinadorRepository,
      UsuarioRepository usuarioRepository,
      // Injetado pela INTERFACE: é o tipo declarado no campo que o ArchUnit inspeciona, e nomear
      // ProvisionamentoCarteiraService aqui faria identidade alcançar carteira.dominio.
      ProvisionamentoCarteira provisionamentoCarteira,
      AporteToken aporteToken) {
    this.patrocinadorRepository = patrocinadorRepository;
    this.usuarioRepository = usuarioRepository;
    this.provisionamentoCarteira = provisionamentoCarteira;
    this.aporteToken = aporteToken;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<UUID> usuarioIdDoPatrocinadorAtivo(String transportadoraSlug) {
    if (transportadoraSlug == null || transportadoraSlug.isBlank()) {
      return Optional.empty();
    }
    return patrocinadorRepository.buscarUsuarioIdAtivoPorSlug(
        transportadoraSlug.toLowerCase(Locale.ROOT));
  }

  /**
   * Cria o patrocinador: conta-titular, carteira e a relação com a transportadora.
   *
   * <p>Os três numa transação só, e isso não é conveniência. Um patrocinador sem carteira faria o
   * primeiro webhook estourar com {@code RecursoNaoEncontradoException} lá dentro do caminho de
   * valor, com o {@code FOR UPDATE} do ponto de custódia na mão — e a transportadora receberia 500
   * num endpoint cujo contrato é nunca devolver 5xx para estado de negócio.
   *
   * <p>{@code garantirCarteira} roda com {@code MANDATORY}, então ele EXIGE esta transação; chamar
   * este método fora de uma falharia na hora, que é o comportamento desejado.
   */
  @Auditavel(acao = "PATROCINADOR_CADASTRADO", entidade = "patrocinador")
  @Transactional
  public PatrocinadorResponse cadastrar(String nome, String transportadoraSlug, Instant agora) {
    String slug = transportadoraSlug.toLowerCase(Locale.ROOT);

    // Recusa amigável ANTES de escrever. Sem ela, a segunda tentativa com o mesmo slug bateria em
    // uk_patrocinador_slug e viraria 500 com mensagem de driver — que a regra de erro do projeto
    // proíbe expor, e que não diz ao ADMIN o que ele fez de errado.
    if (patrocinadorRepository.existsByTransportadoraSlug(slug)) {
      throw new RegraNegocioVioladaException(
          "Já existe patrocinador para a transportadora " + slug + ".");
    }

    String email = "patrocinador@" + slug + ".local";
    String handle = handleDe(slug);

    // Derivados do slug, que é único — então a colisão só acontece se um usuário HUMANO já tiver
    // tomado o handle. Checar é mais barato que explicar um 500 depois.
    if (usuarioRepository.existsByEmail(email) || usuarioRepository.existsByHandle(handle)) {
      throw new RegraNegocioVioladaException(
          "Identificador derivado do slug " + slug + " já está em uso.");
    }

    UUID usuarioId = UUID.randomUUID();
    usuarioRepository.save(Usuario.paraPatrocinador(usuarioId, nome, email, handle, agora));

    // A carteira nasce junto com o titular, exatamente como no registro de gente. Provisionar sob
    // demanda no primeiro débito seria pior: a criação aconteceria dentro da transação do webhook,
    // que já segura locks.
    provisionamentoCarteira.garantirCarteira(usuarioId);

    Patrocinador patrocinador = new Patrocinador(UUID.randomUUID(), usuarioId, slug, nome, agora);
    patrocinadorRepository.save(patrocinador);

    return PatrocinadorResponse.de(patrocinador);
  }

  @Transactional(readOnly = true)
  public List<PatrocinadorResponse> listar() {
    return patrocinadorRepository.findAll().stream().map(PatrocinadorResponse::de).toList();
  }

  @Auditavel(acao = "PATROCINADOR_DESATIVADO", entidade = "patrocinador")
  @Transactional
  public PatrocinadorResponse desativar(UUID patrocinadorId) {
    Patrocinador patrocinador = buscar(patrocinadorId);
    patrocinador.desativar();
    patrocinadorRepository.save(patrocinador);
    return PatrocinadorResponse.de(patrocinador);
  }

  /**
   * Aporta tokens na carteira do patrocinador. É o ÚNICO ponto de emissão de moeda do sistema.
   *
   * <p>A resolução do titular e o crédito acontecem na MESMA transação. Separá-las em duas — uma
   * leitura aqui, um {@code AporteToken.aportar} lá — deixaria uma janela entre descobrir o
   * usuarioId e travar a carteira dele, e a chave de idempotência é derivada justamente desse
   * usuarioId: com o patrocínio alterado no meio, a chave protegeria a carteira errada.
   *
   * <p>Emissão duplicada não é recuperável depois. Se um retry cunhasse duas vezes, ledger e
   * projeção ficariam ambos errados na mesma direção e a reconciliação seguiria verde — por isso a
   * idempotência aqui é obrigatória, e não um conforto de cliente.
   */
  @Auditavel(acao = "PATROCINADOR_APORTE", entidade = "patrocinador")
  @Transactional
  public AporteResponse aportar(
      UUID patrocinadorId, long tokens, String chaveDoCliente, Instant agora) {

    Patrocinador patrocinador = buscar(patrocinadorId);

    // Patrocínio encerrado não recebe aporte: emitir moeda para uma relação que acabou seria criar
    // token que nenhuma missão vai gastar, e a conservação passaria a depender de quantos contratos
    // foram desativados com saldo dentro.
    if (!patrocinador.isAtivo()) {
      throw new RegraNegocioVioladaException(
          "Patrocinador " + patrocinador.getTransportadoraSlug() + " está inativo.");
    }

    ResultadoAporte resultado =
        aporteToken.aportar(
            patrocinador.getUsuarioId(),
            tokens,
            ChaveIdempotencia.aportePatrocinador(patrocinador.getUsuarioId(), chaveDoCliente),
            agora);

    return new AporteResponse(
        patrocinador.getId(),
        patrocinador.getUsuarioId(),
        resultado.lancamentoId(),
        resultado.saldoTokens(),
        resultado.replay());
  }

  private Patrocinador buscar(UUID patrocinadorId) {
    return patrocinadorRepository
        .findById(patrocinadorId)
        .orElseThrow(() -> new RecursoNaoEncontradoException("Patrocinador não encontrado."));
  }

  /**
   * {@code transportadora-dev} vira {@code transportadora_dev}.
   *
   * <p>O hífen do slug não é aceito em handle pela convenção do projeto, e o handle existe aqui só
   * para satisfazer {@code uk_usuario_handle} — ninguém procura patrocinador por @.
   */
  private static String handleDe(String slug) {
    return slug.replace('-', '_');
  }
}
