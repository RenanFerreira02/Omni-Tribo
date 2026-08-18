import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { contarNaoLidos, listarAlertas, marcarAlertaLido } from '@/api/alertas';
import type { ErroApi } from '@/api/erros';
import type { AlertaResponse, PaginaResponse } from '@/api/tipos';

export const chavesAlertas = {
  todos: ['alertas'] as const,
  lista: (apenasNaoLidos: boolean) => ['alertas', 'lista', apenasNaoLidos] as const,
  contagem: ['alertas', 'contagem'] as const,
};

export function useAlertasInfinitos(apenasNaoLidos = false) {
  return useInfiniteQuery<PaginaResponse<AlertaResponse>, ErroApi>({
    queryKey: chavesAlertas.lista(apenasNaoLidos),
    initialPageParam: 0,
    queryFn: ({ pageParam }) => listarAlertas(pageParam as number, apenasNaoLidos),
    getNextPageParam: (ultima) => (ultima.ultima ? undefined : ultima.pagina + 1),
  });
}

/**
 * Contador do badge.
 *
 * `staleTime` curto e `refetchOnWindowFocus`: o número precisa reagir a uma notificação que chegou
 * enquanto o app estava em segundo plano. É a única consulta do app com essa característica — as
 * demais são disparadas por navegação.
 */
export function useContagemNaoLidos() {
  return useQuery<number, ErroApi>({
    queryKey: chavesAlertas.contagem,
    queryFn: contarNaoLidos,
    staleTime: 30_000,
    refetchOnWindowFocus: true,
  });
}

/**
 * Marcar como lida, COM atualização otimista.
 *
 * Aqui a otimista se justifica e o rollback quase não importa: a operação é idempotente no servidor
 * e trivialmente repetível. O que o usuário não pode ver é a notificação continuar em negrito
 * depois de ele a ter aberto.
 */
export function useMarcarLido() {
  const queryClient = useQueryClient();

  return useMutation<AlertaResponse, ErroApi, { id: string }>({
    mutationFn: ({ id }) => marcarAlertaLido(id),
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey: chavesAlertas.contagem });
      const anterior = queryClient.getQueryData<number>(chavesAlertas.contagem);
      if (typeof anterior === 'number' && anterior > 0) {
        queryClient.setQueryData(chavesAlertas.contagem, anterior - 1);
      }
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: chavesAlertas.todos });
    },
    throwOnError: false,
  });
}
