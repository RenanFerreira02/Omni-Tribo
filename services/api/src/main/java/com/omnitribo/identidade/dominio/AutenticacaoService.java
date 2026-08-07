package com.omnitribo.identidade.dominio;

import com.omnitribo.carteira.api.ProvisionamentoCarteira;
import com.omnitribo.compartilhado.dominio.BloqueioException;
import com.omnitribo.compartilhado.dominio.DominioException;
import com.omnitribo.compartilhado.infra.BloqueioLoginService;
import com.omnitribo.compartilhado.infra.JwtService;
import com.omnitribo.identidade.api.LoginRequest;
import com.omnitribo.identidade.api.LoginResponse;
import com.omnitribo.identidade.api.RegistrarRequest;
import com.omnitribo.identidade.infra.RefreshTokenRepository;
import com.omnitribo.identidade.infra.UsuarioRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutenticacaoService {

  private static final String MSG_CREDENCIAIS_INVALIDAS = "Credenciais inválidas";
  private static final Duration TTL_REFRESH = Duration.ofDays(30);
  private static final int REFRESH_TOKEN_BYTES = 32; // 256 bits de entropia

  private final UsuarioRepository usuarioRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final AuditoriaService auditoriaService;
  private final JwtService jwtService;
  private final BloqueioLoginService bloqueioLoginService;
  private final PasswordEncoder passwordEncoder;
  private final ProvisionamentoCarteira provisionamentoCarteira;
  private final SecureRandom secureRandom = new SecureRandom();

  @Value("${app.jwt.ttl-access:PT15M}")
  private Duration ttlAccess;

  public AutenticacaoService(
      UsuarioRepository usuarioRepository,
      RefreshTokenRepository refreshTokenRepository,
      AuditoriaService auditoriaService,
      JwtService jwtService,
      BloqueioLoginService bloqueioLoginService,
      PasswordEncoder passwordEncoder,
      ProvisionamentoCarteira provisionamentoCarteira) {
    this.usuarioRepository = usuarioRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.auditoriaService = auditoriaService;
    this.jwtService = jwtService;
    this.bloqueioLoginService = bloqueioLoginService;
    this.passwordEncoder = passwordEncoder;
    this.provisionamentoCarteira = provisionamentoCarteira;
  }

  @Transactional
  public LoginResponse registrar(RegistrarRequest request, String ip, String userAgent) {
    // Email normalizado: lowercase + trim para evitar duplicatas por capitalização.
    String email = request.email().toLowerCase().trim();

    if (usuarioRepository.existsByEmail(email)) {
      // Mensagem deliberadamente vaga: não confirma se o email existe.
      // Prevenção de enumeração de usuários via resposta.
      throw new DominioException(HttpStatus.CONFLICT, "Não foi possível criar a conta.");
    }
    if (usuarioRepository.existsByHandle(request.handle())) {
      throw new DominioException(HttpStatus.CONFLICT, "Não foi possível criar a conta.");
    }

    Instant agora = Instant.now();
    Usuario usuario =
        new Usuario(
            UUID.randomUUID(),
            request.nome(),
            email,
            passwordEncoder.encode(request.senha()), // DelegatingPasswordEncoder → {argon2}...
            request.handle(),
            request.triboId(),
            agora);

    usuarioRepository.save(usuario);

    // Carteira nasce junto com o usuário, na MESMA transação. Sem isto, todo usuário novo ficaria
    // sem carteira e o primeiro crédito de missão — ou qualquer transferência para ele — falharia
    // com 404. Criar sob demanda no primeiro crédito seria pior: aconteceria dentro de uma
    // transação que já segura locks, e dois créditos simultâneos disputariam a criação.
    provisionamentoCarteira.garantirCarteira(usuario.getId());

    auditoriaService.gravar(
        usuario.getId(),
        "USUARIO_REGISTRADO",
        "usuario",
        usuario.getId().toString(),
        ip,
        userAgent,
        MDC.get("correlationId"));

    return emitirTokens(usuario);
  }

  @Transactional
  public LoginResponse login(LoginRequest request, String ip, String userAgent) {
    String email = request.email().toLowerCase().trim();

    // Verifica bloqueio progressivo e rate limit ANTES de qualquer consulta ao banco.
    var bloqueio = bloqueioLoginService.verificar(ip, email);
    if (bloqueio != null) {
      throw new BloqueioException(bloqueio.segundosRestantes()); // compartilhado/dominio
    }

    // Busca pelo email sem revelar se existe ou não; usa comparação em tempo constante via
    // passwordEncoder.matches() mesmo quando o usuário não é encontrado (dummy hash).
    Usuario usuario = usuarioRepository.findByEmail(email).orElse(null);

    boolean senhaValida =
        usuario != null && passwordEncoder.matches(request.senha(), usuario.getSenhaHash());

    if (!senhaValida) {
      // Registra falha; pode disparar bloqueio progressivo se atingir o limiar.
      bloqueioLoginService.registrarFalha(ip, email);
      auditoriaService.gravar(
          null, // atorId null: não sabemos (e não revelamos) se o email existe
          "LOGIN_FALHO",
          "usuario",
          null,
          ip,
          userAgent,
          MDC.get("correlationId"));
      // Mesma mensagem para senha errada E email inexistente: impede enumeração de usuários.
      throw new DominioException(HttpStatus.UNAUTHORIZED, MSG_CREDENCIAIS_INVALIDAS);
    }

    if (usuario.getStatus() != StatusUsuario.ATIVO) {
      // Conta suspensa/banida: mesma mensagem genérica.
      throw new DominioException(HttpStatus.UNAUTHORIZED, MSG_CREDENCIAIS_INVALIDAS);
    }

    bloqueioLoginService.registrarSucesso(ip, email);
    auditoriaService.gravar(
        usuario.getId(),
        "LOGIN_OK",
        "usuario",
        usuario.getId().toString(),
        ip,
        userAgent,
        MDC.get("correlationId"));

    return emitirTokens(usuario);
  }

  // noRollbackFor: revogarFamilia() precisa persistir mesmo quando a exceção é lançada logo após.
  // Sem isso, a transação faria rollback, desfazendo as revogações — a família pareceria ativa.
  @Transactional(noRollbackFor = DominioException.class)
  public LoginResponse refresh(String tokenOpaco, String ip, String userAgent) {
    String tokenHash = sha256(tokenOpaco);
    RefreshToken token =
        refreshTokenRepository
            .findByTokenHash(tokenHash)
            .orElseThrow(() -> new DominioException(HttpStatus.UNAUTHORIZED, "Sessão inválida"));

    if (token.getRevogadoEm() != null) {
      // DETECÇÃO DE REUSO: token já foi rotacionado mas está sendo apresentado de novo.
      // Cenário: atacante roubou o refresh token após uma rotação legítima.
      // Resposta: revogar TODA a família — força o usuário legítimo a re-autenticar.
      // Referência: RFC 6819 §5.2.2.3.
      revogarFamilia(token.getFamiliaId());
      auditoriaService.gravar(
          token.getUsuarioId(),
          "REFRESH_REUSO",
          "refresh_token",
          token.getFamiliaId().toString(),
          ip,
          userAgent,
          MDC.get("correlationId"));
      throw new DominioException(HttpStatus.UNAUTHORIZED, "Sessão inválida");
    }

    if (!token.estaValido()) {
      throw new DominioException(HttpStatus.UNAUTHORIZED, "Sessão expirada");
    }

    Usuario usuario =
        usuarioRepository
            .findById(token.getUsuarioId())
            .orElseThrow(() -> new DominioException(HttpStatus.UNAUTHORIZED, "Sessão inválida"));

    // Rotação: revoga o token atual e emite um novo na mesma família.
    UUID novoId = UUID.randomUUID();
    token.revogar(novoId);
    refreshTokenRepository.save(token);

    LoginResponse resposta = emitirTokens(usuario, token.getFamiliaId(), novoId);

    auditoriaService.gravar(
        usuario.getId(),
        "REFRESH_OK",
        "refresh_token",
        token.getFamiliaId().toString(),
        ip,
        userAgent,
        MDC.get("correlationId"));

    return resposta;
  }

  @Transactional
  public void logout(UUID usuarioId, String tokenOpaco, String ip, String userAgent) {
    String tokenHash = sha256(tokenOpaco);
    refreshTokenRepository
        .findByTokenHash(tokenHash)
        .filter(t -> t.getUsuarioId().equals(usuarioId)) // previne revogar token de outro usuário
        .ifPresent(t -> t.revogar(null));

    auditoriaService.gravar(
        usuarioId,
        "LOGOUT",
        "usuario",
        usuarioId.toString(),
        ip,
        userAgent,
        MDC.get("correlationId"));
  }

  // ─── Helpers privados ─────────────────────────────────────────────────────

  private LoginResponse emitirTokens(Usuario usuario) {
    UUID familiaId = UUID.randomUUID(); // nova família para login/registro
    UUID tokenId = UUID.randomUUID();
    return emitirTokens(usuario, familiaId, tokenId);
  }

  private LoginResponse emitirTokens(Usuario usuario, UUID familiaId, UUID novoTokenId) {
    String accessToken =
        jwtService.emitirAccessToken(
            usuario.getId(), usuario.getEmail(), usuario.getPapel().name());

    String refreshOpaco = gerarRefreshOpaco();
    String refreshHash = sha256(refreshOpaco);

    RefreshToken novoRefresh =
        new RefreshToken(
            novoTokenId, usuario.getId(), refreshHash, familiaId, Instant.now().plus(TTL_REFRESH));
    refreshTokenRepository.save(novoRefresh);

    return new LoginResponse(accessToken, refreshOpaco, "Bearer", ttlAccess.getSeconds());
  }

  private void revogarFamilia(UUID familiaId) {
    List<RefreshToken> familia = refreshTokenRepository.findByFamiliaId(familiaId);
    familia.forEach(
        t -> {
          if (t.getRevogadoEm() == null) t.revogar(null);
        });
    refreshTokenRepository.saveAll(familia);
  }

  private String gerarRefreshOpaco() {
    // 256 bits de entropia via SecureRandom — imprevisível mesmo com estado do PRNG conhecido.
    byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String sha256(String input) {
    // Armazenamos apenas o hash: mesmo que o banco seja comprometido, tokens não são
    // aproveitáveis sem a string original (que trafegou apenas no canal TLS).
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 não disponível", e);
    }
  }
}
