import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render as renderRtl } from '@testing-library/react-native';
import type { ReactElement, ReactNode } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

/**
 * Métricas fixas de safe area.
 *
 * `SafeAreaProvider` mede a tela de verdade, e num ambiente de teste a medição nunca chega —
 * `useSafeAreaInsets` fica pendurado e a árvore não renderiza os filhos. Passar `initialMetrics`
 * resolve com valores determinísticos, que é o que um teste quer: notch fixo, não notch real.
 */
const METRICAS_DE_TESTE = {
  frame: { x: 0, y: 0, width: 390, height: 844 },
  insets: { top: 47, left: 0, right: 0, bottom: 34 },
};

/** QueryClient novo por teste: cache compartilhado faria um teste enxergar o dado do anterior. */
function novoQueryClient() {
  return new QueryClient({
    defaultOptions: {
      // Retry desligado: com ele, um teste de erro esperaria três tentativas e o backoff antes de
      // a tela mostrar a falha — e falharia por timeout em vez de por comportamento.
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
}

/**
 * `render` é ASSÍNCRONO na RNTL 14 — precisa de `await`.
 *
 * Sem o await, `screen` continua vazio e todo `getByTestId` estoura com "`render` function has not
 * been called", que não sugere em nada que o problema é a falta de uma palavra-chave. `fireEvent`
 * também virou assíncrono na mesma versão.
 */
export async function render(ui: ReactElement) {
  const cliente = novoQueryClient();
  function Envolucro({ children }: { children: ReactNode }) {
    return (
      <SafeAreaProvider initialMetrics={METRICAS_DE_TESTE}>
        <QueryClientProvider client={cliente}>{children}</QueryClientProvider>
      </SafeAreaProvider>
    );
  }
  const resultado = await renderRtl(ui, { wrapper: Envolucro });
  return { ...resultado, queryClient: cliente };
}
