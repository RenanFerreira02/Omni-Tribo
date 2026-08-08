import { useInfiniteQuery, useQuery } from '@tanstack/react-query';

import { buscarCarteira, listarLancamentos } from '@/api/carteira';
import type { ErroApi } from '@/api/erros';
import type { CarteiraResponse, LancamentoResponse, PaginaResponse } from '@/api/tipos';

export const chavesCarteira = {
  raiz: ['carteira'] as const,
  saldo: ['carteira', 'saldo'] as const,
  lancamentos: ['carteira', 'lancamentos'] as const,
};

export function useCarteira() {
  return useQuery<CarteiraResponse, ErroApi>({
    queryKey: chavesCarteira.saldo,
    queryFn: buscarCarteira,
  });
}

export function useLancamentos() {
  return useInfiniteQuery<PaginaResponse<LancamentoResponse>, ErroApi>({
    queryKey: chavesCarteira.lancamentos,
    initialPageParam: 0,
    queryFn: ({ pageParam }) => listarLancamentos(pageParam as number),
    getNextPageParam: (ultima) => (ultima.ultima ? undefined : ultima.pagina + 1),
  });
}
