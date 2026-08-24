package com.omnitribo.identidade.dominio;

import com.omnitribo.compartilhado.dominio.RecursoNaoEncontradoException;
import com.omnitribo.identidade.api.UsuarioBuscaResponse;
import com.omnitribo.identidade.infra.TriboRepository;
import com.omnitribo.identidade.infra.UsuarioRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Busca de vizinho por handle EXATO, dentro da própria tribo.
 *
 * <p>Fecha a Pendência #3: a tela de transferência pedia o UUID do destinatário como texto —
 * funcionava e era inutilizável, porque ninguém sabe o próprio UUID, muito menos o do vizinho.
 *
 * <h2>Por que busca exata e não listagem</h2>
 *
 * <p>Listar membros da tribo daria a qualquer autenticado um mapa social do bairro, e como a
 * transferência é restrita à mesma tribo, essa lista também seria uma lista de alvos — é a decisão
 * que o javadoc de {@code TriboController} registra, e ela fica de pé. Aqui quem já sabe o
 * {@code @} do vizinho o encontra; quem não sabe não descobre nada.
 *
 * <h2>O que protege, e o que não protege</h2>
 *
 * <p>Três defesas, e <b>nenhuma delas é o código de status</b>:
 *
 * <ol>
 *   <li>restrição à MESMA TRIBO — o endpoint só responde sobre o grupo a que quem pergunta já
 *       pertence;
 *   <li>match EXATO — sem prefixo, sem similaridade, sem "você quis dizer";
 *   <li>teto próprio de requisições, bem abaixo do limite geral de leitura.
 * </ol>
 *
 * <p>Handle inexistente, handle de outra tribo e conta inutilizável respondem <b>o mesmo 404</b>, o
 * que os torna indistinguíveis. Ver ADR 0028 para o argumento completo — inclusive por que um 200
 * vazio seria exatamente igual de seguro, e por que a escolha se decidiu por outro critério.
 */
@Service
public class BuscaUsuarioService {

  private final UsuarioRepository usuarioRepository;
  private final TriboRepository triboRepository;
  private final ConsultaAfiliacaoService consultaAfiliacaoService;

  public BuscaUsuarioService(
      UsuarioRepository usuarioRepository,
      TriboRepository triboRepository,
      // Classe concreta, e não a porta: os dois vivem no PRÓPRIO módulo `identidade`, e a regra do
      // ArchUnit é sobre alcançar `dominio` de OUTRO módulo. Mesma isenção de
      // `EstornoFinanciamentoService` em `missoes`.
      ConsultaAfiliacaoService consultaAfiliacaoService) {
    this.usuarioRepository = usuarioRepository;
    this.triboRepository = triboRepository;
    this.consultaAfiliacaoService = consultaAfiliacaoService;
  }

  /**
   * @param quemPergunta id vindo do JWT — NUNCA do corpo, da query ou de um cabeçalho. Se viesse do
   *     cliente, a restrição de tribo seria decorativa: bastaria alegar ser de outra para procurar
   *     em qualquer uma
   * @throws RecursoNaoEncontradoException quando não há vizinho ATIVO com esse handle na tribo de
   *     quem pergunta — os três motivos possíveis colapsam nesta única resposta
   */
  @Transactional(readOnly = true)
  public UsuarioBuscaResponse porHandle(UUID quemPergunta, String handle) {
    // Uma única mensagem para os três casos. Diferenciá-las aqui recriaria o oráculo que a
    // indistinguibilidade existe para fechar — e "não existe" versus "não é da sua tribo" é
    // exatamente a diferença que um enumerador quer.
    RecursoNaoEncontradoException naoEncontrado =
        new RecursoNaoEncontradoException("Nenhum vizinho com esse @ na sua tribo.");

    Usuario encontrado =
        usuarioRepository.buscarPorHandleExato(handle.trim()).orElseThrow(() -> naoEncontrado);

    // A regra de tribo mora em `mesmaTribo` e não numa comparação aqui: ela já trata o caso de
    // `tribo_id` nulo dos DOIS lados como "não é a mesma tribo". Comparar dois Optional vazios
    // daria `true` e liberaria a busca entre todos os usuários sem tribo.
    if (!consultaAfiliacaoService.mesmaTribo(quemPergunta, encontrado.getId())) {
      throw naoEncontrado;
    }

    // Buscar a própria conta é permitido e inofensivo: a pessoa já sabe quem é. Bloquear exigiria
    // um
    // caso especial que só serviria para deixar a resposta MENOS uniforme.
    String nomeDaTribo =
        triboRepository.findById(encontrado.getTriboId()).map(Tribo::getNome).orElse(null);

    return new UsuarioBuscaResponse(
        encontrado.getId(), encontrado.getHandle(), encontrado.getNome(), nomeDaTribo);
  }
}
