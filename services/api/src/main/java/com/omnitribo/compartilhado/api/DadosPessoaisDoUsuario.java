package com.omnitribo.compartilhado.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contribuição de um módulo para a exportação de dados pessoais (LGPD art. 18, V).
 *
 * <p><b>Por que uma porta com plugins, e não um serviço que lê tudo.</b> A exportação precisa de
 * missões, lançamentos, check-ins e identidade — quatro módulos. Um serviço central que os lesse
 * teria de alcançar {@code dominio} e {@code infra} alheios, que a regra do ArchUnit proíbe; e
 * quatro portas nomeadas (uma por módulo) fariam {@code identidade} depender de {@code missoes},
 * que já depende de {@code identidade} — um ciclo entre módulos por causa de um relatório.
 *
 * <p>Com esta interface em {@code compartilhado}, cada módulo implementa a SUA seção e o Spring
 * injeta a lista. Quem monta o arquivo nunca nomeia nenhum módulo, e um módulo novo entra na
 * exportação só por existir. O acoplamento aponta todo para {@code compartilhado}, que é
 * dependência de todos por design.
 *
 * <p><b>Consequência que vale saber:</b> esquecer de implementar esta interface num módulo novo não
 * quebra nada — a exportação simplesmente sai incompleta, em silêncio. É a mesma classe de
 * armadilha do {@code @Auditavel}, e o teste de exportação existe para travar as seções conhecidas.
 */
public interface DadosPessoaisDoUsuario {

  /** Nome da seção no arquivo exportado, ex. {@code "missoes"}. Precisa ser único entre módulos. */
  String secao();

  /**
   * Tudo o que este módulo guarda sobre o titular, em forma legível.
   *
   * <p>Chaves em português, valores já desnormalizados: o destinatário é uma PESSOA exercendo um
   * direito, não um sistema. Id interno de outra entidade não ajuda ninguém e só amplia o que sai.
   *
   * <p>NUNCA inclua segredo — hash de senha, token, chave de idempotência. O titular tem direito
   * aos dados dele, não ao material criptográfico que protege a conta.
   */
  List<Map<String, Object>> exportar(UUID usuarioId);
}
