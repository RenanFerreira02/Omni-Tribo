import { me, refresh } from '@/api/auth';
import { paraErroApi, sessaoAcabou } from '@/api/erros';
import { lerRefreshPersistido, useSessao } from '@/stores/sessao';

/**
 * Restauração de sessão no boot.
 *
 * O access token vive só em memória e morre junto com o processo, então TODO início de app começa
 * deslogado do ponto de vista da rede. O que sobrevive é o refresh, no keystore. Rotacioná-lo aqui
 * é o que faz o usuário reabrir o app já dentro.
 *
 * Falhar é normal e não é erro: refresh expirado (30 dias), revogado, ou primeira execução.
 *
 * **Mas nem toda falha é a sessão ter acabado, e o tratamento passou a distinguir.** O comentário
 * anterior dizia que "em todos os casos o desfecho é o mesmo, e por isso não há tratamento por
 * causa" — isso deixou de ser verdade quando o backend passou a aplicar rate limit ao
 * `/auth/refresh`. Um 429 no boot, ou o Wi-Fi ainda subindo, apagava o refresh do keystore e
 * derrubava uma sessão de 30 dias que estava perfeitamente válida. O usuário abria o app deslogado
 * sem ter feito nada, e não havia como voltar atrás.
 *
 * Agora só `naoAutenticado`/`acessoNegado` limpam o cofre. Nos demais casos a sessão em memória
 * fica vazia (o access token nunca sobrevive ao processo mesmo), o app abre na tela de login, e o
 * refresh CONTINUA guardado — então a próxima abertura, ou o próximo login, reaproveita.
 *
 * `concluirRestauracao` roda no `finally` porque a navegação fica bloqueada até ele: esquecê-lo em
 * algum caminho deixaria o app parado na splash para sempre.
 */
export async function restaurarSessao(): Promise<void> {
  const estado = useSessao.getState();
  try {
    const persistido = await lerRefreshPersistido();
    if (!persistido) return;

    const tokens = await refresh(persistido);
    await estado.definirSessao(tokens);
    estado.definirUsuario(await me());
  } catch (falha) {
    // A limpeza tem `try` PRÓPRIO, e não é zelo excessivo: sem ele, uma falha aqui dentro escapa do
    // `catch` que já está tratando outra falha e **mascara o erro original**. Foi assim que um
    // `getItemAsync` inexistente na web chegou ao usuário disfarçado de `deleteItemAsync is not a
    // function`, apontando para a linha errada. Um caminho de recuperação que produz exceção nova
    // destrói o diagnóstico do problema que ele deveria estar resolvendo.
    try {
      if (sessaoAcabou(paraErroApi(falha))) {
        await estado.encerrar();
      } else {
        // Obstáculo temporário (429, rede): zera a memória e PRESERVA o cofre.
        estado.limparMemoria();
      }
    } catch {
      // Nada a fazer, e nada a perder: o desfecho pretendido já é sessão limpa e tela de login. Se
      // apagar o refresh do keystore falhou, o estado em memória zera do mesmo jeito e o servidor
      // recusa o token órfão na próxima tentativa.
    }
  } finally {
    estado.concluirRestauracao();
  }
}
