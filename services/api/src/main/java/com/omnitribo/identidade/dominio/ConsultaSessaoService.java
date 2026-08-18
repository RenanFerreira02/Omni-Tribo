package com.omnitribo.identidade.dominio;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.omnitribo.identidade.api.AutenticadoPrincipal;
import com.omnitribo.identidade.api.ConsultaSessao;
import com.omnitribo.identidade.infra.UsuarioRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Estado da conta por requisição, com cache curto.
 *
 * <p><b>O trade-off, para ser defendido de viva voz.</b> A janela em que uma conta anonimizada
 * continua escrevendo cai de <b>15 minutos</b> (o TTL do access token) para <b>no máximo 60
 * segundos</b> — e para ZERO pelos caminhos que chamam {@link #invalidar}. O custo é um {@code
 * SELECT} por chave primária por usuário por MINUTO, não por requisição: com 100 usuários ativos
 * são ~100 consultas/min, duas ordens de grandeza abaixo do tráfego que as gera.
 *
 * <p><b>Isto parece contradizer o CLAUDE.md e não contradiz.</b> A regra de lá diz para não
 * enriquecer {@code GET /auth/me} com "uma consulta com joins em toda abertura do app". O que ela
 * proíbe é o JOIN do perfil (tribo, conquistas, nível), que é caro e é outra pergunta. O que entra
 * aqui é uma leitura de cinco colunas por PK, sem join, servida do cache na esmagadora maioria das
 * vezes. {@code /auth/me} continua se resolvendo dos claims.
 *
 * <p><b>{@code expireAfterWrite}, nunca {@code expireAfterAccess}.</b> O que importa é a IDADE do
 * dado, não o uso: com expiração por acesso, um token quente nunca releria o banco e a janela
 * voltaria a ser ilimitada — exatamente o defeito que este serviço existe para fechar.
 *
 * <p><b>O cache guarda {@code Optional}, inclusive vazio.</b> Cachear a AUSÊNCIA é o que impede o
 * mecanismo de virar amplificador: sem isso, um token bem assinado cujo {@code sub} não existe (ou
 * uma conta já apagada) forçaria uma consulta ao banco por requisição, e o controle antifraude
 * seria o próprio vetor de carga.
 */
@Service
public class ConsultaSessaoService implements ConsultaSessao {

  /**
   * 60 s é o TETO da janela para qualquer caminho que ESQUEÇA de invalidar — inclusive UPDATE
   * manual no banco. Os caminhos que invalidam fecham em zero.
   */
  private static final Duration VALIDADE = Duration.ofSeconds(60);

  private static final int MAXIMO_ENTRADAS = 20_000;

  private final UsuarioRepository usuarioRepository;

  private final Cache<UUID, Optional<AutenticadoPrincipal>> cache =
      Caffeine.newBuilder().expireAfterWrite(VALIDADE).maximumSize(MAXIMO_ENTRADAS).build();

  public ConsultaSessaoService(UsuarioRepository usuarioRepository) {
    this.usuarioRepository = usuarioRepository;
  }

  @Override
  public Optional<AutenticadoPrincipal> sessaoAtiva(UUID usuarioId) {
    return cache.get(usuarioId, this::carregar);
  }

  @Override
  public void invalidar(UUID usuarioId) {
    cache.invalidate(usuarioId);
  }

  /**
   * O principal é montado a partir do BANCO, não dos claims — é isso que faz o papel ser
   * reconferido. Reaproveitar o papel do token deixaria um ADMIN rebaixado com autoridade de ADMIN
   * até o token expirar, que é metade do defeito.
   */
  private Optional<AutenticadoPrincipal> carregar(UUID usuarioId) {
    return usuarioRepository
        .buscarEstadoDaConta(usuarioId)
        .filter(EstadoDaConta::ativa)
        .map(e -> new AutenticadoPrincipal(e.id(), e.email(), e.papel()));
  }
}
