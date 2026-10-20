import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react-native';
import type { ReactNode } from 'react';

import { INTERVALO_TELAO_MS, chavesImpacto, useImpacto } from '@/features/impacto/hooks';
import { useSessao } from '@/stores/sessao';
import { CARTEIRA } from '@/testes/fixtures';

/**
 * O telão repete a consulta; a tela comum, não. Conferido na OPÇÃO registrada no cache, e não
 * esperando o relógio: a repetição é do React Query, e o que este app decide é só o intervalo.
 */
async function montar(aoVivo?: boolean) {
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const envolucro = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={cliente}>{children}</QueryClientProvider>
  );
  const resultado = await renderHook(
    () => useImpacto(aoVivo === undefined ? undefined : { aoVivo }),
    {
      wrapper: envolucro,
    },
  );
  // As opções registradas são as do useQuery; o tipo do cache as declara como QueryOptions genérico.
  const intervalo = () =>
    (
      cliente.getQueryCache().find({ queryKey: chavesImpacto.todas })?.options as
        { refetchInterval?: unknown } | undefined
    )?.refetchInterval;
  return { resultado, intervalo, cliente };
}

beforeEach(() => {
  useSessao.setState({
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    usuario: { id: CARTEIRA.usuarioId, email: 'admin@omnitribo.dev', papel: 'ADMIN' },
  });
});

describe('useImpacto', () => {
  it('ao vivo, repete a cada INTERVALO_TELAO_MS', async () => {
    const { resultado, intervalo, cliente } = await montar(true);
    await waitFor(() => expect(resultado.result.current.isSuccess).toBe(true));
    expect(intervalo()).toBe(INTERVALO_TELAO_MS);
    cliente.clear();
  });

  it('sem o modo ao vivo, não repete', async () => {
    const { resultado, intervalo, cliente } = await montar();
    await waitFor(() => expect(resultado.result.current.isSuccess).toBe(true));
    expect(intervalo()).toBe(false);
    cliente.clear();
  });
});
