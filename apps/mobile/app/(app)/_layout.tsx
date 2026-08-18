import { Redirect, Stack } from 'expo-router';

import { useSessao } from '@/stores/sessao';
import { cores } from '@/theme';

/**
 * Guarda das telas autenticadas que ficam FORA das abas.
 *
 * **Por que este grupo existe.** `beneficios`, `missao/criar` e `missao/[id]` viviam na raiz de
 * `app/` e eram as únicas rotas autenticadas sem guarda nenhuma — só `(auth)` e `(tabs)` tinham. Pela
 * navegação interna elas só são alcançáveis a partir das abas, então na prática pareciam protegidas.
 * O que quebrava essa suposição é o deep link: `omnitribo://missao/<id>` monta a tela direto, e sem
 * guarda ela dispara um GET autenticado sem token — o mesmo cenário que `app/index.tsx` já
 * argumentava ter evitado.
 *
 * O grupo entre parênteses NÃO entra na URL: as rotas continuam sendo `/beneficios`,
 * `/missao/criar` e `/missao/{id}`. Nenhum `router.push` existente precisou mudar.
 *
 * `<Redirect>` durante a renderização, e não em efeito — mesmo padrão e mesma razão de `(tabs)`: a
 * tela protegida nunca chega a montar, então nenhuma query autenticada dispara sem token.
 */
export default function LayoutApp() {
  const accessToken = useSessao((estado) => estado.accessToken);

  if (!accessToken) return <Redirect href="/(auth)/login" />;

  return (
    <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: cores.papel } }}>
      <Stack.Screen name="beneficios" />
      <Stack.Screen name="missao/criar" />
      <Stack.Screen name="missao/[id]" options={{ headerShown: true, title: 'Missão' }} />
    </Stack>
  );
}
