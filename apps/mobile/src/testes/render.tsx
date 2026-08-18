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
      // `gcTime: 0` nas MUTATIONS também, e isto não é simetria estética — é o que faz o processo
      // terminar. O default é 5 minutos: toda mutation executada num teste (marcar aviso como lido,
      // transferir, criar missão) deixa uma entrada no cache com um `setTimeout` de 300 s pendurado,
      // e um timer vivo segura o event loop. Medido: a suíte de telas roda em 1,8 s e o processo só
      // saía 5 min e 2 s depois. Some quando há vários workers, porque o jest mata o worker à força
      // ("worker process has failed to exit gracefully") — e reaparece exatamente onde não há
      // worker para matar: runner de 2 núcleos, que executa in-band. Ou seja, o sintoma é um build
      // que trava sem nenhum teste vermelho.
      mutations: { retry: false, gcTime: 0 },
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
