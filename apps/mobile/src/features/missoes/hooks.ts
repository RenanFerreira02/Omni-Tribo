import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { paraErroApi, type ErroApi } from '@/api/erros';
import {
  aplicarAcao,
  buscarMissao,
  listarMissoes,
  missoesProximas,
  registrarCheckin,
  type AcaoMissao,
} from '@/api/missoes';
import type {
  CategoriaMissao,
  MissaoProximaResponse,
  MissaoResponse,
  PaginaResponse,
  RegistrarCheckinRequest,
} from '@/api/tipos';

const TAMANHO_PAGINA = 20;

export const chaves = {
  missoes: ['missoes'] as const,
  lista: (categoria?: CategoriaMissao) => ['missoes', 'lista', categoria ?? 'todas'] as const,
  proximas: (lat: number, lon: number, categoria?: CategoriaMissao) =>
    // Coordenada arredondada a 4 casas (~11 m) na chave: sem isso, cada tremida do GPS geraria uma
    // entrada de cache nova e o radar refetcharia sem parar enquanto o usuário está parado.
    ['missoes', 'proximas', lat.toFixed(4), lon.toFixed(4), categoria ?? 'todas'] as const,
  detalhe: (id: string) => ['missoes', 'detalhe', id] as const,
};

/** Lista paginada. `GET /missoes` NÃO traz distância — para isso existe o radar. */
export function useMissoesInfinitas(categoria?: CategoriaMissao) {
  return useInfiniteQuery<PaginaResponse<MissaoResponse>, ErroApi>({
    queryKey: chaves.lista(categoria),
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      listarMissoes({
        categoria,
        status: 'ABERTA',
        pagina: pageParam as number,
        tamanho: TAMANHO_PAGINA,
      }),
    getNextPageParam: (ultimaPagina) => (ultimaPagina.ultima ? undefined : ultimaPagina.pagina + 1),
  });
}

/** Radar: array puro, ordenado por distância, só missões ABERTA. */
export function useMissoesProximas(
  coordenada: { lat: number; lon: number } | null,
  categoria?: CategoriaMissao,
) {
  return useQuery<MissaoProximaResponse[], ErroApi>({
    queryKey: coordenada
      ? chaves.proximas(coordenada.lat, coordenada.lon, categoria)
      : ['missoes', 'proximas', 'sem-coordenada'],
    enabled: coordenada !== null,
    queryFn: () =>
      missoesProximas({
        lat: coordenada!.lat,
        lon: coordenada!.lon,
        raioMetros: 2000,
        categoria,
        limite: 50,
      }),
    // O backend cacheia o radar por 30 s. Repetir isso no cliente evita uma ida à rede a cada
    // reentrada na aba sem atrasar o dado além do que o servidor já atrasa.
    staleTime: 30_000,
  });
}

export function useMissao(id: string) {
  return useQuery<MissaoResponse, ErroApi>({
    queryKey: chaves.detalhe(id),
    queryFn: () => buscarMissao(id),
  });
}

export function useAcaoMissao(id: string) {
  const queryClient = useQueryClient();

  return useMutation<MissaoResponse, ErroApi, { acao: AcaoMissao; motivo?: string }>({
    mutationFn: ({ acao, motivo }) => aplicarAcao(id, acao, motivo),
    onSuccess: (missao) => {
      // A resposta JÁ é o estado novo: escrever no cache evita um GET redundante e o "pisca" de
      // dado velho enquanto ele volta.
      queryClient.setQueryData(chaves.detalhe(id), missao);
      // As listas mudaram de composição (a missão saiu de ABERTA, ou voltou para ela).
      queryClient.invalidateQueries({ queryKey: chaves.missoes });
    },
    throwOnError: false,
  });
}

export function useCheckin(id: string) {
  const queryClient = useQueryClient();

  return useMutation<
    MissaoResponse,
    ErroApi,
    { corpo: RegistrarCheckinRequest; chaveIdempotencia: string }
  >({
    mutationFn: ({ corpo, chaveIdempotencia }) => registrarCheckin(id, corpo, chaveIdempotencia),
    onSuccess: (missao) => {
      queryClient.setQueryData(chaves.detalhe(id), missao);
      queryClient.invalidateQueries({ queryKey: chaves.missoes });
    },
    throwOnError: false,
  });
}

/** Normaliza o erro de qualquer um dos hooks acima para o tipo que a UI ramifica. */
export function erroDe(erro: unknown): ErroApi | null {
  if (erro === null || erro === undefined) return null;
  return paraErroApi(erro);
}
