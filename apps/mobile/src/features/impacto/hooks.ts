import { useQuery } from '@tanstack/react-query';

import { buscarImpacto } from '@/api/impacto';
import type { ErroApi } from '@/api/erros';
import type { ImpactoResponse } from '@/api/tipos';

export const chavesImpacto = {
  todas: ['impacto'] as const,
};

/**
 * O painel, apurado no servidor a cada chamada.
 *
 * `staleTime` zero (o default) e nenhum cache próprio: o backend deliberadamente não tem cache
 * (ADR 0029), porque uma segunda fonte de verdade para um painel de auditoria é pior que uma
 * consulta a mais numa tela que um ADMIN abre raramente. Repetir esse cache aqui recriaria o
 * problema do lado do cliente — alguém leria um número de dez minutos atrás achando que é de agora.
 *
 * `retry: false`: o erro esperado desta rota é 403, e insistir num "não" não muda a resposta.
 *
 * `aoVivo` é o TELÃO do estande (`/impacto?telao=1`): a tela repete a consulta sozinha, para o
 * visitante ver o número mudar quando a entrega dele é concluída no celular. Fora dele, nada se
 * repete — o servidor apura tudo na hora (ADR 0029), e repetir sem ninguém olhando é carga à toa.
 */
export const INTERVALO_TELAO_MS = 5_000;

export function useImpacto({ aoVivo = false }: { aoVivo?: boolean } = {}) {
  return useQuery<ImpactoResponse, ErroApi>({
    queryKey: chavesImpacto.todas,
    queryFn: buscarImpacto,
    retry: false,
    refetchInterval: aoVivo ? INTERVALO_TELAO_MS : false,
    refetchIntervalInBackground: aoVivo,
  });
}
