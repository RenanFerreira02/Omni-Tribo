package com.omnitribo.missoes;

import com.omnitribo.compartilhado.dominio.Coordenadas;
import com.omnitribo.missoes.dominio.AtorMissao;
import com.omnitribo.missoes.dominio.CategoriaMissao;
import com.omnitribo.missoes.dominio.EventoMissao;
import com.omnitribo.missoes.dominio.Missao;
import com.omnitribo.missoes.dominio.StatusMissao;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Construtores de Missao e de atores para os testes de domínio. */
public final class MissaoFixture {

  public static final UUID CRIADOR = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  public static final UUID EXECUTOR = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
  public static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000000ad");
  public static final UUID ESTRANHO = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

  public static final Instant AGORA = Instant.parse("2026-08-06T12:00:00Z");

  private MissaoFixture() {}

  /** Missão no status pedido, sempre com executor preenchido para permitir eventos de EXECUTOR. */
  public static Missao no(StatusMissao status) {
    Missao missao =
        new Missao(
            UUID.randomUUID(),
            CRIADOR,
            CategoriaMissao.ENTREGA,
            "Entrega solidária de teste",
            "Descrição da missão de teste.",
            status,
            100,
            new BigDecimal("25.00"),
            10L,
            Coordenadas.ponto(new BigDecimal("-23.5629"), new BigDecimal("-46.6996")),
            null,
            null,
            "05422030",
            "Rua dos Pinheiros",
            "Pinheiros",
            "São Paulo",
            "SP",
            50,
            null,
            null,
            AGORA.minus(1, ChronoUnit.HOURS),
            AGORA.plus(2, ChronoUnit.DAYS),
            AGORA.minus(1, ChronoUnit.DAYS));
    missao.setExecutorId(EXECUTOR);
    return missao;
  }

  /** Ator autorizado a disparar o evento sobre a missão. */
  public static AtorMissao atorCorretoPara(EventoMissao evento, Missao missao) {
    return switch (evento.atorEsperado()) {
      case CRIADOR -> AtorMissao.usuario(missao.getCriadorId());
      case EXECUTOR -> AtorMissao.usuario(missao.getExecutorId());
      case CANDIDATO -> AtorMissao.usuario(ESTRANHO);
      case ADMIN -> AtorMissao.admin(ADMIN);
      case SISTEMA -> AtorMissao.sistema();
    };
  }

  /** Ator que a máquina deve recusar com 403 para este evento. */
  public static AtorMissao atorErradoPara(EventoMissao evento, Missao missao) {
    return switch (evento.atorEsperado()) {
      // O criador não pode aceitar a própria missão: seria autonegócio, com ele confirmando
      // a si mesmo e liberando o crédito sem contraparte.
      case CANDIDATO -> AtorMissao.usuario(missao.getCriadorId());
      default -> AtorMissao.usuario(ESTRANHO);
    };
  }
}
