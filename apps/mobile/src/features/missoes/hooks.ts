import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import type { ErroApi } from '@/api/erros';
import { chavesCarteira } from '@/features/carteira/hooks';
import { chavesPerfil } from '@/features/perfil/hooks';
import {
  aplicarAcao,
  buscarMissao,
  criarMissao,
  listarMissoes,
  missoesProximas,
  previaRecompensa,
  registrarCheckin,
  type AcaoMissao,
} from '@/api/missoes';
import type {
  CategoriaMissao,
  CriarMissaoRequest,
  MissaoProximaResponse,
  MissaoResponse,
  PaginaResponse,
  PreviaRecompensaResponse,
  RegistrarCheckinRequest,
} from '@/api/tipos';

const TAMANHO_PAGINA = 20;

export const chaves = {
  missoes: ['missoes'] as const,
  /** Prefixos, para invalidar uma FAMÍLIA sem varrer o detalhe (que já vem correto na resposta). */
  todasAsListas: ['missoes', 'lista'] as const,
  todosOsRadares: ['missoes', 'proximas'] as const,
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

/**
 * Para onde cada ação leva a missão. Usado só na PREVISÃO otimista — a verdade vem do servidor.
 *
 * EXPORTADO para que `acoes.test.ts` possa cruzar esta tabela com a `MATRIZ` de ações disponíveis.
 * As duas juntas espelham a máquina de estados do backend, e nada garantia que concordassem: uma
 * ação oferecida sem previsão aqui deixa o botão sem efeito visível até o servidor responder.
 */
export const STATUS_OTIMISTA: Partial<Record<AcaoMissao, MissaoResponse['status']>> = {
  publicar: 'ABERTA',
  aceitar: 'ACEITA',
  iniciar: 'EM_ANDAMENTO',
  desistir: 'ABERTA',
  cancelar: 'CANCELADA',
  contestar: 'EM_DISPUTA',
  confirmar: 'CONCLUIDA',
};

/**
 * Aplica uma ação com ATUALIZAÇÃO OTIMISTA e rollback.
 *
 * Antes era write-through: a tela só mudava quando o servidor respondia, e "Aceitar" ficava com
 * spinner por um round-trip inteiro no caminho mais disputado do app. Agora o status muda na hora e
 * volta atrás se o servidor recusar.
 *
 * **`cancelQueries` antes de tocar o cache é obrigatório.** Sem isso, um refetch em voo — disparado
 * pelo foco na tela, por exemplo — resolveria DEPOIS da escrita otimista e sobrescreveria a
 * previsão com o estado anterior; a tela pareceria ter revertido sozinha, sem erro nenhum.
 *
 * O 409 é o caso que motiva tudo isto: duas pessoas aceitando a mesma missão. Quem perde vê a
 * reversão e a mensagem, em vez de uma tela que afirma "ACEITA" e um erro solto embaixo.
 */
export function useAcaoMissao(id: string) {
  const queryClient = useQueryClient();

  return useMutation<
    MissaoResponse,
    ErroApi,
    { acao: AcaoMissao; motivo?: string },
    { anterior: MissaoResponse | undefined }
  >({
    mutationFn: ({ acao, motivo }) => aplicarAcao(id, acao, motivo),

    onMutate: async ({ acao }) => {
      await queryClient.cancelQueries({ queryKey: chaves.detalhe(id) });
      const anterior = queryClient.getQueryData<MissaoResponse>(chaves.detalhe(id));

      const status = STATUS_OTIMISTA[acao];
      if (anterior && status) {
        // Só o STATUS é previsto. `executorId`, `aceitaEm` e a recompensa congelada dependem de
        // decisões do servidor, e inventá-las aqui faria a tela exibir dado falso por um instante.
        queryClient.setQueryData<MissaoResponse>(chaves.detalhe(id), { ...anterior, status });
      }
      return { anterior };
    },

    onError: (_erro, _variaveis, contexto) => {
      // Rollback exato: repõe o objeto que estava lá, não uma reconstrução.
      if (contexto?.anterior) {
        queryClient.setQueryData(chaves.detalhe(id), contexto.anterior);
      }
    },

    onSuccess: (missao) => {
      // A resposta JÁ é o estado novo: escrever no cache evita um GET redundante e o "pisca" de
      // dado velho enquanto ele volta.
      queryClient.setQueryData(chaves.detalhe(id), missao);

      // Carteira e perfil TAMBÉM mudaram, e não eram invalidados. `confirmar` credita tokens e XP
      // no servidor; sem isto, quem estivesse com a carteira montada continuava vendo o saldo
      // anterior — e a recompensa que acabou de entrar só aparecia num remount da aba.
      queryClient.invalidateQueries({ queryKey: chavesCarteira.todas });
      queryClient.invalidateQueries({ queryKey: chavesPerfil.perfil });
    },

    onSettled: (_dados, erro) => {
      // Só quando FALHOU. Invalidar sempre desfazia o `setQueryData` logo acima — `['missoes']` é
      // prefixo de `['missoes','detalhe',id]`, então a query da tela ativa era marcada stale e
      // refetchada na hora. O comentário dizia "evita um GET redundante" e a linha seguinte fazia
      // o GET acontecer de qualquer jeito.
      //
      // No erro a invalidação é necessária de verdade: depois de um 409 o estado real é outro, e
      // nem o cache nem o update otimista sabem qual é.
      if (erro) {
        queryClient.invalidateQueries({ queryKey: chaves.missoes });
        return;
      }
      // No sucesso, só as LISTAS: a composição delas mudou (a missão saiu de ABERTA, ou voltou),
      // mas o detalhe já está correto pela resposta.
      queryClient.invalidateQueries({ queryKey: chaves.todasAsListas });
      queryClient.invalidateQueries({ queryKey: chaves.todosOsRadares });
    },

    throwOnError: false,
  });
}

/** Criação. A missão nasce em RASCUNHO; publicar é ação à parte. */
export function useCriarMissao() {
  const queryClient = useQueryClient();

  return useMutation<MissaoResponse, ErroApi, CriarMissaoRequest>({
    mutationFn: (corpo) => criarMissao(corpo),
    onSuccess: (missao) => {
      queryClient.setQueryData(chaves.detalhe(missao.id), missao);
      queryClient.invalidateQueries({ queryKey: chaves.missoes });
    },
    throwOnError: false,
  });
}

/**
 * Prévia da recompensa, com debounce de 400 ms aplicado por quem chama.
 *
 * `retry: false` e `throwOnError` implícito desligado: a prévia é ENFEITE INFORMATIVO. Se falhar, a
 * criação continua — a tela avisa que a recompensa será calculada ao publicar. Tentar de novo três
 * vezes só atrasaria o formulário de quem digitou algo que o servidor recusa.
 */
export function usePreviaRecompensa(corpo: CriarMissaoRequest | null) {
  return useQuery<PreviaRecompensaResponse, ErroApi>({
    queryKey: ['missoes', 'previa', corpo],
    enabled: corpo !== null,
    queryFn: () => previaRecompensa(corpo!),
    retry: false,
    staleTime: 60_000,
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
