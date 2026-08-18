import { useRouter } from 'expo-router';
import * as Linking from 'expo-linking';
import { useEffect } from 'react';

import { rotaSeguraDe, type RotaDeepLink } from '@/lib/deepLink';
import { useSessao } from '@/stores/sessao';

/**
 * Trata links externos, e é o ponto onde `rotaSeguraDe` de fato entra em ação.
 *
 * **Sem este hook, o validador seria decoração.** O `scheme: 'omnitribo'` está registrado, então o
 * Expo Router já navegava sozinho para qualquer caminho recebido — inclusive para telas
 * autenticadas, com um id não validado descendo até a URL da API. Escrever a validação e não ligá-la
 * repetiria o defeito que ela veio corrigir: uma defesa que existe no papel e não no caminho.
 *
 * Ordem das decisões, e cada uma tem uma razão:
 *  1. **Link inválido → ignora.** Não redireciona para lugar nenhum: o app segue o fluxo normal de
 *     abertura. Mandar para uma tela "quase certa" seria adivinhar o que um link forjado queria.
 *  2. **Sem sessão → guarda a intenção e manda para o login.** O layout de `(app)` recusaria a
 *     navegação de qualquer jeito; interceptar aqui evita o piscar da tela protegida.
 *  3. **Com sessão → navega.**
 *
 * A intenção pendente é consumida uma vez só, no login seguinte — um link guardado indefinidamente
 * levaria alguém a uma tela inesperada dias depois.
 */
let rotaPendente: RotaDeepLink | null = null;

/** Rota que um deep link pediu antes de haver sessão. Consome e esquece. */
export function consumirRotaPendente(): RotaDeepLink | null {
  const rota = rotaPendente;
  rotaPendente = null;
  return rota;
}

export function useDeepLink(): void {
  const router = useRouter();
  const accessToken = useSessao((estado) => estado.accessToken);

  useEffect(() => {
    function tratar(url: string | null) {
      const rota = rotaSeguraDe(url);
      if (!rota) return;

      if (!accessToken) {
        rotaPendente = rota;
        return;
      }
      router.push(rota);
    }

    // App fechado: o link que o abriu.
    void Linking.getInitialURL().then(tratar);

    // App aberto: link recebido enquanto ele já estava rodando.
    const inscricao = Linking.addEventListener('url', ({ url }) => tratar(url));
    return () => inscricao.remove();
  }, [accessToken, router]);
}
