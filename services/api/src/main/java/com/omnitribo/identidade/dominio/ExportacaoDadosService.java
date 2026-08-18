package com.omnitribo.identidade.dominio;

import com.omnitribo.compartilhado.api.DadosPessoaisDoUsuario;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exportação de dados pessoais (LGPD art. 18, V).
 *
 * <p>Monta o arquivo a partir das contribuições de cada módulo, injetadas como {@code List} de
 * {@link DadosPessoaisDoUsuario}. Este serviço não conhece nenhum módulo pelo nome — é o que evita
 * o ciclo {@code identidade → missoes → identidade} e mantém a regra do ArchUnit satisfeita. Um
 * módulo novo entra na exportação só por publicar um bean.
 *
 * <p>Sem paginação e sem streaming, deliberadamente. Um titular deste sistema tem dezenas de
 * missões e centenas de lançamentos, não milhões; e um arquivo de exportação paginado obrigaria a
 * pessoa a montar o próprio arquivo a partir de páginas para exercer um direito.
 */
@Service
public class ExportacaoDadosService {

  private final List<DadosPessoaisDoUsuario> fontes;

  public ExportacaoDadosService(List<DadosPessoaisDoUsuario> fontes) {
    this.fontes = List.copyOf(fontes);
  }

  /**
   * Uma transação só, em modo leitura, para o arquivo inteiro.
   *
   * <p>Sem isso, cada seção leria num instante diferente e o arquivo poderia mostrar uma missão
   * concluída sem o lançamento que a pagou — um retrato inconsistente de um sistema que estava
   * consistente o tempo todo.
   */
  @Transactional(readOnly = true)
  public Map<String, Object> exportar(UUID usuarioId) {
    Map<String, Object> arquivo = new LinkedHashMap<>();
    arquivo.put("geradoEm", Instant.now());
    arquivo.put(
        "aviso",
        "Exportação de dados pessoais do titular, conforme LGPD art. 18, V. Não inclui senha, "
            + "tokens de acesso nem chaves de controle interno.");

    for (DadosPessoaisDoUsuario fonte : fontes) {
      arquivo.put(fonte.secao(), fonte.exportar(usuarioId));
    }
    return arquivo;
  }
}
