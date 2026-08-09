import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import {
  buscarCarteira,
  listarLancamentos,
  sacar,
  transferirTokens,
  type RespostaSaque,
} from '@/api/carteira';
import type { ErroApi } from '@/api/erros';
import type {
  CarteiraResponse,
  LancamentoResponse,
  PaginaResponse,
  TransferenciaResponse,
} from '@/api/tipos';

export const chavesCarteira = {
  raiz: ['carteira'] as const,
  /** Raiz para invalidar saldo e extrato de uma vez depois de uma escrita. */
  todas: ['carteira'] as const,
  saldo: ['carteira', 'saldo'] as const,
  lancamentos: ['carteira', 'lancamentos'] as const,
};

/**
 * Transferência de tokens.
 *
 * SEM atualização otimista, e a assimetria com as ações de missão é deliberada: aqui o que mudaria
 * na tela é SALDO. Um número que aparece debitado e volta atrás depois de um 422 de saldo
 * insuficiente é a pior forma de mostrar dinheiro — o usuário acredita no primeiro valor que viu.
 *
 * A chave de idempotência vem de fora, gerada junto com a intenção, para que um retry de rede
 * repita a mesma chave em vez de transferir duas vezes.
 */
export function useTransferirTokens() {
  const queryClient = useQueryClient();

  return useMutation<
    TransferenciaResponse,
    ErroApi,
    { destinatarioId: string; tokens: number; chaveIdempotencia: string; mensagem?: string }
  >({
    mutationFn: ({ destinatarioId, tokens, chaveIdempotencia, mensagem }) =>
      transferirTokens(destinatarioId, tokens, chaveIdempotencia, mensagem),
    onSuccess: () => {
      // Saldo e extrato mudaram os dois. Invalidar em vez de escrever: a resposta traz o saldo do
      // remetente, mas não a linha nova do extrato.
      queryClient.invalidateQueries({ queryKey: chavesCarteira.todas });
    },
    throwOnError: false,
  });
}

/**
 * Saque. Hoje SEMPRE responde 422 `saque-desabilitado` — está desligado por configuração, não
 * quebrado (ADR 0009).
 *
 * O hook existe para a tela poder exercitar o erro tipado num teste. A UI não deixa o usuário
 * chegar aqui: o botão aparece DESABILITADO, com a explicação ao lado.
 */
export function useSacar() {
  return useMutation<RespostaSaque, ErroApi, { valorBrl: number; chaveIdempotencia: string }>({
    mutationFn: ({ valorBrl, chaveIdempotencia }) => sacar(valorBrl, chaveIdempotencia),
    throwOnError: false,
  });
}

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
