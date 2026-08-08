import { Redirect } from 'expo-router';

import { useSessao } from '@/stores/sessao';

/**
 * Porta de entrada: decide entre (auth) e (tabs).
 *
 * A decisão vive numa rota própria, e não num `useEffect` com `router.replace` dentro dos layouts,
 * porque `<Redirect>` acontece durante a renderização — antes de a tela protegida montar e disparar
 * as queries dela. Redirecionar depois do mount deixaria um piscar da tela de missões (e um GET
 * autenticado sem token) para quem não está logado.
 */
export default function Entrada() {
  const accessToken = useSessao((estado) => estado.accessToken);
  return <Redirect href={accessToken ? '/(tabs)' : '/(auth)/login'} />;
}
