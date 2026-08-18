import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { paraErroApi, valeTentarDeNovo } from '@/api/erros';
import { useDeepLink } from '@/features/navegacao/useDeepLink';
import { restaurarSessao } from '@/features/auth/restaurarSessao';
import { useSessao } from '@/stores/sessao';
import { cores } from '@/theme';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Repetir um 403, um 404 ou um 422 é gastar bateria para receber a mesma recusa. Só
      // `conflitoConcorrencia` e falha de rede melhoram com insistência — a decisão sai do `type`,
      // nunca do status ou do texto.
      retry: (tentativas, erro) => tentativas < 2 && valeTentarDeNovo(paraErroApi(erro)),
      staleTime: 15_000,
    },
    mutations: { retry: false },
  },
});

export default function LayoutRaiz() {
  // Lê o `restaurando` do STORE, e não um `useState` local.
  //
  // O store sempre teve essa flag, com um comentário dizendo que ela "segura o redirecionamento das
  // rotas" — e nenhuma rota a lia. Quem segurava era um `useState` daqui, e havia até uma asserção
  // de teste afirmando que `restaurando: false` destrava o app, o que não era verdade. Dois portões
  // para a mesma coisa, e o documentado era o desligado.
  const restaurando = useSessao((estado) => estado.restaurando);

  useEffect(() => {
    void restaurarSessao();
  }, []);

  // Todo link externo passa por aqui e é VALIDADO antes de virar navegação. Ver `src/lib/deepLink.ts`
  // — o esquema está registrado desde sempre, mas nada conferia rota nem formato de parâmetro.
  useDeepLink();

  // `restaurarSessao` chama `concluirRestauracao` no `finally`, então isto destrava em qualquer
  // desfecho — inclusive falha. Sem essa garantia, o app ficaria na splash para sempre.
  if (restaurando) return null;

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <QueryClientProvider client={queryClient}>
          <StatusBar style="dark" />
          <Stack
            screenOptions={{ headerShown: false, contentStyle: { backgroundColor: cores.papel } }}
          >
            <Stack.Screen name="(auth)" />
            <Stack.Screen name="(tabs)" />
            {/* `(app)` agrupa as telas autenticadas que ficam fora das abas — beneficios,
                missao/criar e missao/[id]. Elas estavam soltas na raiz, sem guarda de sessão, e o
                header de "Missão" era declarado aqui. Agora quem cuida das duas coisas é
                `app/(app)/_layout.tsx`. */}
            <Stack.Screen name="(app)" />
          </Stack>
        </QueryClientProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
