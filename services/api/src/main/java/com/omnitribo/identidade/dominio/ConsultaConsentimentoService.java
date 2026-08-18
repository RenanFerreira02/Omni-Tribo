package com.omnitribo.identidade.dominio;

import com.omnitribo.identidade.api.ConsultaConsentimento;
import com.omnitribo.identidade.infra.ConsentimentoRepository;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementação de {@link ConsultaConsentimento}. Ver o javadoc da porta para o contrato. */
@Service
public class ConsultaConsentimentoService implements ConsultaConsentimento {

  private final ConsentimentoRepository consentimentoRepository;

  public ConsultaConsentimentoService(ConsentimentoRepository consentimentoRepository) {
    this.consentimentoRepository = consentimentoRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> usuariosComConsentimento(Collection<UUID> triboIds, Collection<String> tipos) {

    // Coleção vazia num IN gera SQL inválido no PostgreSQL ("IN ()"), então a guarda não é
    // micro-otimização: sem ela, um ponto de custódia sem nenhuma tribo no raio derrubaria o
    // despacho com erro de sintaxe em vez de simplesmente não notificar ninguém.
    if (triboIds.isEmpty() || tipos.isEmpty()) {
      return List.of();
    }

    // Deduplica antes de contar: se o chamador pedir NOTIFICACAO duas vezes, `exigidos` seria 2 e
    // NINGUÉM passaria, porque o DISTINCT ON devolve no máximo uma linha por tipo. Falha silenciosa
    // e difícil de enxergar — o resultado seria uma lista vazia, indistinguível de "ninguém
    // consentiu".
    Set<String> tiposUnicos = Set.copyOf(tipos);

    return consentimentoRepository.usuariosComConsentimento(
        Set.copyOf(triboIds), tiposUnicos, tiposUnicos.size());
  }
}
