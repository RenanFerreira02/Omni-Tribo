import { me, refresh } from '@/api/auth';
import { lerRefreshPersistido, useSessao } from '@/stores/sessao';

/**
 * Restauração de sessão no boot.
 *
 * O access token vive só em memória e morre junto com o processo, então TODO início de app começa
 * deslogado do ponto de vista da rede. O que sobrevive é o refresh, no keystore. Rotacioná-lo aqui
 * é o que faz o usuário reabrir o app já dentro.
 *
 * Falhar é normal e não é erro: refresh expirado (30 dias), revogado, ou primeira execução. Em
 * todos os casos o desfecho é o mesmo — sessão limpa e tela de login —, e por isso não há
 * tratamento por causa. `concluirRestauracao` roda no `finally` porque a navegação fica bloqueada
 * até ele: esquecê-lo em algum caminho deixaria o app parado na splash para sempre.
 */
export async function restaurarSessao(): Promise<void> {
  const estado = useSessao.getState();
  try {
    const persistido = await lerRefreshPersistido();
    if (!persistido) return;

    const tokens = await refresh(persistido);
    await estado.definirSessao(tokens);
    estado.definirUsuario(await me());
  } catch {
    await estado.encerrar();
  } finally {
    estado.concluirRestauracao();
  }
}
