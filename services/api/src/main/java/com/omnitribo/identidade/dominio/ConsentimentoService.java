package com.omnitribo.identidade.dominio;

import com.omnitribo.identidade.api.AtualizarConsentimentoRequest;
import com.omnitribo.identidade.api.ConsentimentoResponse;
import com.omnitribo.identidade.infra.ConsentimentoRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consentimentos do titular. Tabela APPEND-ONLY: cada mudança é linha nova.
 *
 * <p>Sobrescrever a linha existente seria mais simples e destruiria a única evidência que importa
 * numa disputa — a de que a pessoa consentiu em tal data, sob tal versão do texto. Um histórico de
 * escolhas também é o que permite mostrar "você revogou notificações em março", em vez de um estado
 * sem passado.
 */
@Service
public class ConsentimentoService {

  private final ConsentimentoRepository consentimentoRepository;

  public ConsentimentoService(ConsentimentoRepository consentimentoRepository) {
    this.consentimentoRepository = consentimentoRepository;
  }

  /**
   * Estado atual de TODOS os tipos, inclusive os que a pessoa nunca decidiu.
   *
   * <p>Tipo sem nenhuma linha volta como {@code concedido = false}, e não sumido da lista: a tela
   * precisa desenhar o interruptor mesmo antes da primeira escolha, e "ausente" e "recusado" têm o
   * mesmo efeito prático — não há consentimento.
   */
  @Transactional(readOnly = true)
  public List<ConsentimentoResponse> listar(UUID usuarioId) {
    Map<TipoConsentimento, Consentimento> maisRecentePorTipo =
        consentimentoRepository.findByUsuarioIdOrderByCriadoEmDesc(usuarioId).stream()
            // A lista já vem do mais recente para o mais antigo; em empate de instante, o primeiro
            // encontrado vence e o merge descarta o resto.
            .collect(
                Collectors.toMap(
                    Consentimento::getTipo,
                    Function.identity(),
                    (maisNovo, maisAntigo) -> maisNovo));

    return Arrays.stream(TipoConsentimento.values())
        .map(
            tipo ->
                Optional.ofNullable(maisRecentePorTipo.get(tipo))
                    .map(
                        c ->
                            new ConsentimentoResponse(
                                tipo.name(), c.isConcedido(), c.getVersaoTexto(), c.getCriadoEm()))
                    .orElseGet(() -> new ConsentimentoResponse(tipo.name(), false, null, null)))
        .toList();
  }

  /**
   * Registra uma escolha. Sempre INSERT, nunca UPDATE.
   *
   * <p>O IP fica nulo aqui de propósito. A coluna existe e seria fácil preenchê-la com o {@code
   * X-Forwarded-For}, mas esse header é escolhido pelo cliente atrás de um proxy que ainda não
   * temos — gravar um valor forjável como evidência é pior do que não gravar nada, porque parece
   * prova.
   */
  @Transactional
  public ConsentimentoResponse registrar(
      UUID usuarioId, TipoConsentimento tipo, AtualizarConsentimentoRequest request) {

    Consentimento registro =
        consentimentoRepository.save(
            new Consentimento(
                UUID.randomUUID(),
                usuarioId,
                tipo,
                Boolean.TRUE.equals(request.concedido()),
                request.versaoTexto(),
                null,
                Instant.now()));

    return new ConsentimentoResponse(
        tipo.name(), registro.isConcedido(), registro.getVersaoTexto(), registro.getCriadoEm());
  }
}
