import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { paraErroApi, valeTentarDeNovo } from '@/api/erros';
import { restaurarSessao } from '@/features/auth/restaurarSessao';
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
  const [pronto, setPronto] = useState(false);

  useEffect(() => {
    restaurarSessao().finally(() => setPronto(true));
  }, []);

  if (!pronto) return null;

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
            <Stack.Screen
              name="missao/[id]"
              options={{ headerShown: true, title: 'Missão', presentation: 'card' }}
            />
          </Stack>
        </QueryClientProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
