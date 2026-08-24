package com.omnitribo.identidade.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * A relação comercial entre uma transportadora e o titular de carteira que financia o pote das
 * missões de retirada dela.
 *
 * <p>Existe porque, até a V23, NADA no sistema ligava o slug do cabeçalho {@code X-Transportadora}
 * a um titular. O slug só aparecia como chave do mapa de segredos em {@code ParametrosWebhook}, e o
 * segredo continua lá — segredo em tabela é segredo em backup e em qualquer {@code make psql}. O
 * que mora aqui é a relação, que não é segredo e precisa de FK, de unicidade e de auditoria.
 *
 * <p>Vive em {@code identidade} porque é uma extensão de {@code usuario}, tabela que este módulo
 * possui. O slug atravessa como {@code String} e não como tipo de {@code logistica}: é chave de
 * negócio, não um conceito importado — mesma disciplina que faz {@code ConsultasGeoespaciais}
 * receber status como String (ADR 0018).
 */
@Entity
@Table(name = "patrocinador")
public class Patrocinador {

  @Id
  @Column(updatable = false, nullable = false)
  private UUID id;

  @Column(name = "usuario_id", nullable = false, updatable = false, unique = true)
  private UUID usuarioId;

  @Column(name = "transportadora_slug", nullable = false, updatable = false, length = 50)
  private String transportadoraSlug;

  @Column(nullable = false, length = 100)
  private String nome;

  @Column(nullable = false)
  private boolean ativo;

  @Column(name = "criado_em", nullable = false, updatable = false)
  private Instant criadoEm;

  protected Patrocinador() {}

  public Patrocinador(
      UUID id, UUID usuarioId, String transportadoraSlug, String nome, Instant criadoEm) {
    this.id = id;
    this.usuarioId = usuarioId;
    // Normalizado na ENTRADA, num ponto só. O HmacWebhookFilter publica o slug verificado já em
    // minúsculas, então guardar com a caixa que o ADMIN digitou faria a resolução do webhook falhar
    // por "Transportadora-Dev" != "transportadora-dev" — e o sintoma seria toda entrega daquela
    // transportadora caindo em SEM_PATROCINIO, que é indistinguível de saldo zerado.
    this.transportadoraSlug = transportadoraSlug.toLowerCase(Locale.ROOT);
    this.nome = nome;
    this.ativo = true;
    this.criadoEm = criadoEm;
  }

  /**
   * Encerra o patrocínio sem apagar a linha.
   *
   * <p>Os lançamentos deste patrocinador continuam no ledger apontando para a carteira dele; apagar
   * a relação deixaria o extrato sem explicação. Inativo faz o webhook cair em SEM_PATROCINIO, que
   * é o desfecho correto — a transportadora fica sabendo que reenviar não adianta.
   */
  public void desativar() {
    this.ativo = false;
  }

  public void reativar() {
    this.ativo = true;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUsuarioId() {
    return usuarioId;
  }

  public String getTransportadoraSlug() {
    return transportadoraSlug;
  }

  public String getNome() {
    return nome;
  }

  public boolean isAtivo() {
    return ativo;
  }

  public Instant getCriadoEm() {
    return criadoEm;
  }
}
