import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render as renderRtl } from '@testing-library/react-native';
import type { ReactElement, ReactNode } from 'react';

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
    return <QueryClientProvider client={cliente}>{children}</QueryClientProvider>;
  }
  const resultado = await renderRtl(ui, { wrapper: Envolucro });
  return { ...resultado, queryClient: cliente };
}
