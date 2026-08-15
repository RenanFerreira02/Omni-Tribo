package com.omnitribo.identidade.api;

import java.util.UUID;

/**
 * A identidade que o próprio Omni-Tribo usa quando cria conteúdo sem humano no caminho.
 *
 * <p>Existe porque {@code missao.criador_id} é {@code NOT NULL REFERENCES usuario(id)} e porque
 * publicar exige {@code AtorEsperado.CRIADOR}, que compara IDENTIDADE e não papel — nem um ADMIN
 * publica missão alheia. {@code AtorMissao.sistema()} tem {@code usuarioId} nulo e por isso não
 * satisfaz nenhum dos dois. A linha correspondente é inserida pela V21, no schema e não no seed,
 * porque produção também precisa dela.
 *
 * <p>A conta é {@code status = 'INATIVO'}, e é o que a torna inofensiva: {@code
 * AutenticacaoService} recusa qualquer status diferente de ATIVO, então nenhuma senha autentica e
 * nenhum token é emitido para ela. Não é backdoor — é alvo de chave estrangeira e um nome para a
 * interface exibir.
 *
 * <p>Porta em {@code identidade/api} pelo motivo de sempre: quem precisa da constante é {@code
 * missoes.dominio}, e o ArchUnit proíbe alcançar {@code identidade.dominio}.
 */
public final class UsuarioSistema {

  private UsuarioSistema() {}

  /** Mesmo UUID gravado pela V21. Mudar aqui sem migration deixa a FK órfã no primeiro webhook. */
  public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  public static boolean ehSistema(UUID usuarioId) {
    return ID.equals(usuarioId);
  }
}
