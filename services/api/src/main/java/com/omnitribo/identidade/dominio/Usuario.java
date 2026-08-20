package com.omnitribo.identidade.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usuario")
public class Usuario {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(nullable = false, length = 100)
  private String nome;

  @Column(nullable = false, length = 255, unique = true)
  private String email;

  @Column(name = "senha_hash", nullable = false, length = 255)
  private String senhaHash;

  @Column(nullable = false, length = 50, unique = true)
  private String handle;

  // triboId: FK para tribo — mesmo módulo identidade
  @Column(name = "tribo_id")
  private UUID triboId;

  @Column(nullable = false)
  private long xp;

  @Column(nullable = false)
  private int nivel;

  @Column(nullable = false)
  private int streak;

  @Column(nullable = false, precision = 2, scale = 1)
  private BigDecimal rating;

  // length = 20, e não 10: 'PATROCINADOR' tem 12 caracteres. A V23 alargou a coluna, e o valor aqui
  // precisa acompanhar — `ddl-auto: validate` do Hibernate não confere comprimento de VARCHAR,
  // então
  // a divergência não apareceria no boot; apareceria como truncamento no INSERT.
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private PapelUsuario papel;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private StatusUsuario status;

  /** Quando o titular exerceu o direito ao esquecimento. Null = conta normal. Ver V18. */
  @Column(name = "anonimizado_em")
  private Instant anonimizadoEm;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private Instant criadoEm;

  @Column(name = "atualizado_em", nullable = false)
  private Instant atualizadoEm;

  @Version
  @Column(nullable = false)
  private int versao;

  protected Usuario() {}

  public Usuario(
      UUID id,
      String nome,
      String email,
      String senhaHash,
      String handle,
      UUID triboId,
      Instant criadoEm) {
    this.id = id;
    this.nome = nome;
    this.email = email;
    this.senhaHash = senhaHash;
    this.handle = handle;
    this.triboId = triboId;
    this.xp = 0;
    this.nivel = 1;
    this.streak = 0;
    this.rating = BigDecimal.ZERO;
    this.papel = PapelUsuario.USUARIO;
    this.status = StatusUsuario.ATIVO;
    this.criadoEm = criadoEm;
    this.atualizadoEm = criadoEm;
  }

  /**
   * A conta-titular de um patrocinador.
   *
   * <p>Fábrica separada, e não um parâmetro {@code papel} no construtor público: o construtor é o
   * caminho do REGISTRO de gente, e abrir o papel ali deixaria "crie-me como ADMIN" a um campo de
   * distância do corpo da requisição. Aqui o papel é fixo no código e não vem de lugar nenhum.
   *
   * <p>{@code INATIVO} é a trava que torna a conta inofensiva — {@code AutenticacaoService} recusa
   * status diferente de ATIVO, então a senha marcadora abaixo não autentica nada. {@code triboId}
   * nulo é semântico: patrocinador não pertence a bairro, e é por isso que ele não passa por {@code
   * FinanciamentoService.validarAutorizacao}, que exige afiliação.
   */
  public static Usuario paraPatrocinador(
      UUID id, String nome, String email, String handle, Instant criadoEm) {
    Usuario patrocinador = new Usuario(id, nome, email, SENHA_INEXISTENTE, handle, null, criadoEm);
    patrocinador.papel = PapelUsuario.PATROCINADOR;
    patrocinador.status = StatusUsuario.INATIVO;
    return patrocinador;
  }

  /**
   * Marcador gravado em {@code senha_hash}, que é NOT NULL e precisa de algum valor.
   *
   * <p>Não tem forma de hash Argon2, então o verificador não consegue casá-lo com senha alguma —
   * nem por acaso, nem por engenharia. Mesma técnica da V21 para o usuário-sistema.
   */
  private static final String SENHA_INEXISTENTE = "CONTA-DE-PATROCINADOR-SEM-SENHA";

  public UUID getId() {
    return id;
  }

  public String getNome() {
    return nome;
  }

  public String getEmail() {
    return email;
  }

  public String getSenhaHash() {
    return senhaHash;
  }

  public String getHandle() {
    return handle;
  }

  public UUID getTriboId() {
    return triboId;
  }

  public long getXp() {
    return xp;
  }

  public void adicionarXp(long quantidade) {
    this.xp += quantidade;
  }

  public int getNivel() {
    return nivel;
  }

  public void setNivel(int nivel) {
    this.nivel = nivel;
  }

  public int getStreak() {
    return streak;
  }

  public PapelUsuario getPapel() {
    return papel;
  }

  public StatusUsuario getStatus() {
    return status;
  }

  public void setStatus(StatusUsuario status) {
    this.status = status;
  }

  /**
   * Descaracteriza o titular, preservando os fatos contábeis. Direito ao esquecimento (LGPD art.
   * 18, VI).
   *
   * <p><b>Um método só, e não setters de e-mail e handle.</b> Anonimizar é uma operação com cinco
   * mutações que só fazem sentido juntas; expor {@code setEmail} para viabilizá-la abriria a porta
   * para trocar e-mail em qualquer lugar do código, sem revalidação e sem reconfirmação. Não existe
   * caso de uso de "mudar e-mail" neste sistema — existe o de esquecer alguém.
   *
   * <p>{@code email} e {@code handle} recebem valores derivados de UUID porque as duas colunas são
   * UNIQUE: esvaziá-las ou usar um literal faria a segunda exclusão de conta violar a constraint.
   *
   * <p>A senha é sobrescrita por um hash impossível de reproduzir — a conta não pode ser reaberta
   * por quem souber a senha antiga, e {@code status = INATIVO} já barra o login antes disso.
   */
  public void anonimizar(Instant quando, String hashInutilizavel) {
    String sufixo = UUID.randomUUID().toString();
    this.nome = "Usuário removido";
    this.email = "removido+" + sufixo + "@anonimizado.invalid";
    this.handle = "removido_" + sufixo.substring(0, 8);
    this.senhaHash = hashInutilizavel;
    this.status = StatusUsuario.INATIVO;
    this.anonimizadoEm = quando;
    this.atualizadoEm = quando;
  }

  public boolean anonimizado() {
    return anonimizadoEm != null;
  }

  public Instant getCriadoEm() {
    return criadoEm;
  }

  public int getVersao() {
    return versao;
  }
}
