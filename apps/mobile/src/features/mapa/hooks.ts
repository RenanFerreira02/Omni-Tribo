import { useQuery } from '@tanstack/react-query';

import type { ErroApi } from '@/api/erros';
import {
  buscarClima,
  buscarEnderecoPorCep,
  buscarPontoCustodia,
  buscarTribo,
  pontosCustodiaProximos,
} from '@/api/lugares';
import type {
  ClimaResponse,
  EnderecoResponse,
  PontoCustodiaResponse,
  TriboResponse,
} from '@/api/tipos';

export const chavesLugares = {
  tribo: (id: string) => ['tribos', id] as const,
  /** Detalhe por id. Prefixo PRÓPRIO: `pontos` é busca por raio, entidade diferente — invalidar
      uma não pode varrer a outra. */
  ponto: (id: string) => ['ponto-custodia', id] as const,
  pontos: (lat: number, lon: number, raio: number) =>
    // Mesma disciplina do radar de missões: coordenada arredondada na chave, senão cada movimento
    // do mapa cria uma entrada de cache nova.
    ['pontos-custodia', lat.toFixed(3), lon.toFixed(3), raio] as const,
  clima: (lat: number, lon: number) => ['clima', lat.toFixed(2), lon.toFixed(2)] as const,
  cep: (cep: string) => ['enderecos', cep] as const,
};

/** Só o detalhe traz o centro geográfico — é ele que o mapa usa quando não há localização. */
export function useTribo(id: string | null | undefined) {
  return useQuery<TriboResponse, ErroApi>({
    queryKey: chavesLugares.tribo(id ?? 'nenhuma'),
    enabled: Boolean(id),
    queryFn: () => buscarTribo(id!),
    staleTime: 15 * 60_000,
  });
}

/**
 * Ponto de custódia por id, para resolver o `pontoCustodiaId` cru de `MissaoResponse`.
 *
 * `retry: false`: ponto desativado responde 404, e o comportamento certo da tela é OMITIR a linha —
 * não insistir três vezes contra um recurso que deixou de existir para depois não mostrar nada.
 */
export function usePontoCustodia(id: string | null | undefined) {
  return useQuery<PontoCustodiaResponse, ErroApi>({
    queryKey: chavesLugares.ponto(id ?? 'nenhum'),
    enabled: Boolean(id),
    queryFn: () => buscarPontoCustodia(id!),
    retry: false,
    staleTime: 15 * 60_000,
  });
}

export function usePontosCustodiaProximos(
  coordenada: { lat: number; lon: number } | null,
  raioMetros = 2000,
) {
  return useQuery<PontoCustodiaResponse[], ErroApi>({
    queryKey: coordenada
      ? chavesLugares.pontos(coordenada.lat, coordenada.lon, raioMetros)
      : ['pontos-custodia', 'sem-coordenada'],
    enabled: coordenada !== null,
    queryFn: () => pontosCustodiaProximos(coordenada!.lat, coordenada!.lon, raioMetros),
    staleTime: 5 * 60_000,
  });
}

/**
 * Clima do card do mapa.
 *
 * `retry: false` porque o modo de falha esperado é o provedor externo fora do ar (503), e insistir
 * três vezes contra um serviço que caiu só atrasa a tela. Quem consome renderiza o card apenas
 * quando há `data`, então qualquer falha — 503, rede, timeout — simplesmente **esconde o card**, sem
 * precisar ramificar por tipo. Clima ausente não é erro de produto.
 *
 * (O comentário anterior dizia que o consumidor verificava `erro.tipo`. Não verifica, e não precisa:
 * a ausência de `data` já é a condição certa. Descrever um mecanismo que não existe faz quem lê
 * procurar um `if` que nunca vai achar.)
 *
 * O servidor já cacheia 10 min por célula de ~1,1 km; repetir isso aqui evita ida à rede ao voltar
 * para a aba.
 */
export function useClima(coordenada: { lat: number; lon: number } | null) {
  return useQuery<ClimaResponse, ErroApi>({
    queryKey: coordenada ? chavesLugares.clima(coordenada.lat, coordenada.lon) : ['clima', 'sem'],
    enabled: coordenada !== null,
    queryFn: () => buscarClima(coordenada!.lat, coordenada!.lon),
    retry: false,
    staleTime: 10 * 60_000,
  });
}

/**
 * CEP → endereço. Quem chama passa o CEP JÁ com debounce de 500 ms.
 *
 * `enabled` só com 8 dígitos: sem isso, cada tecla intermediária viraria uma requisição que o
 * servidor recusaria com 400.
 */
export function useEnderecoPorCep(cep: string) {
  const completo = /^\d{8}$/.test(cep);

  return useQuery<EnderecoResponse, ErroApi>({
    queryKey: chavesLugares.cep(cep),
    enabled: completo,
    queryFn: () => buscarEnderecoPorCep(cep),
    retry: false,
    // CEP não muda. O servidor cacheia sem TTL; aqui o mesmo, dentro da sessão.
    staleTime: Infinity,
  });
}
