import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { listarBeneficios, resgatarBeneficio, type FiltroBeneficios } from '@/api/beneficios';
import type { ErroApi } from '@/api/erros';
import type { BeneficioResponse, PaginaResponse, ResgateResponse } from '@/api/tipos';
import { chavesCarteira } from '@/features/carteira/hooks';

export const chavesBeneficios = {
  todas: ['beneficios'] as const,
  catalogo: (filtro: FiltroBeneficios) => ['beneficios', 'catalogo', filtro] as const,
};

/**
 * Catálogo do bairro.
 *
 * `enabled` desligado com filtro nulo: sem tribo e sem permissão de localização não há recorte, e
 * disparar a consulta produziria um 422 do servidor para uma pergunta que o app não terminou de
 * formular. A tela mostra o estado vazio que ensina, em vez de um erro.
 */
export function useBeneficios(filtro: FiltroBeneficios | null) {
  return useQuery<PaginaResponse<BeneficioResponse>, ErroApi>({
    queryKey: filtro ? chavesBeneficios.catalogo(filtro) : chavesBeneficios.todas,
    queryFn: () => listarBeneficios(filtro as FiltroBeneficios),
    enabled: filtro !== null,
  });
}

/**
 * Resgate — a única operação do app que QUEIMA token.
 *
 * **SEM atualização otimista**, e a razão é a mesma de `useTransferirTokens`, dita lá: o que mudaria
 * na tela é SALDO, e um número que aparece debitado e volta atrás depois de um 422 é a pior forma de
 * mostrar dinheiro — o usuário acredita no primeiro valor que viu. Aqui isso é ainda mais grave,
 * porque o token queimado não volta: se o servidor não confirmou, o saldo não muda.
 *
 * A chave de idempotência vem de FORA, gerada junto com a intenção, para que um retry de rede repita
 * a mesma chave em vez de queimar duas vezes.
 */
export function useResgatar() {
  const queryClient = useQueryClient();

  return useMutation<ResgateResponse, ErroApi, { beneficioId: string; chaveIdempotencia: string }>({
    mutationFn: ({ beneficioId, chaveIdempotencia }) =>
      resgatarBeneficio(beneficioId, chaveIdempotencia),
    onSuccess: () => {
      // Saldo E extrato mudaram. Invalidar a raiz `carteira` cobre os dois de uma vez: a resposta
      // traz o saldo restante, mas não a linha nova do extrato — e é ela que prova a queima.
      queryClient.invalidateQueries({ queryKey: chavesCarteira.todas });
    },
    throwOnError: false,
  });
}
