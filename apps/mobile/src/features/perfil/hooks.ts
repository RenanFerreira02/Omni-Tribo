import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import type { ErroApi } from '@/api/erros';
import {
  buscarPerfil,
  definirConsentimento,
  excluirMinhaConta,
  exportarMeusDados,
  listarConsentimentos,
} from '@/api/perfil';
import type { ConsentimentoResponse, PerfilResponse, TipoConsentimento } from '@/api/tipos';

/**
 * Versão do texto de termos/privacidade vigente NESTE build do app.
 *
 * Constante no cliente, e não vinda do servidor, porque o que precisa ser registrado é a versão que
 * a pessoa realmente viu — e quem sabe isso é o binário que desenhou a tela. Ao publicar um texto
 * novo, mude aqui junto com a copy.
 */
export const VERSAO_TEXTO_CONSENTIMENTO = '2026-08-01';

export const chavesPerfil = {
  perfil: ['perfil'] as const,
  consentimentos: ['perfil', 'consentimentos'] as const,
};

export function usePerfil() {
  return useQuery<PerfilResponse, ErroApi>({
    queryKey: chavesPerfil.perfil,
    queryFn: buscarPerfil,
  });
}

export function useConsentimentos() {
  return useQuery<ConsentimentoResponse[], ErroApi>({
    queryKey: chavesPerfil.consentimentos,
    queryFn: listarConsentimentos,
  });
}

/**
 * Alterna um consentimento.
 *
 * SEM atualização otimista, ao contrário das ações de missão. A diferença é o que está em jogo: um
 * interruptor de consentimento que parece ligado sem ter sido gravado é um registro legal errado na
 * tela do titular. Aqui vale esperar a confirmação do servidor.
 *
 * <b>O sucesso GRAVA a resposta do PUT, em vez de invalidar a lista.</b> Antes era
 * `invalidateQueries`, e isso custava duas coisas. A barata: o PUT já devolve o consentimento
 * atualizado e validado, então o GET seguinte pedia de novo o que estava na mão. A cara: o refetch
 * substituía o ARRAY INTEIRO, e os três `Switch` da tela remontavam juntos a cada toque num deles —
 * era metade do "piscar" que a tela de privacidade tinha.
 *
 * Trocando um item só, os outros dois mantêm identidade referencial e não re-renderizam. Não é
 * atualização otimista: o estado novo vem do servidor, depois da confirmação dele. O que mudou foi
 * de onde ele é lido, não quando.
 */
export function useDefinirConsentimento() {
  const queryClient = useQueryClient();

  return useMutation<
    ConsentimentoResponse,
    ErroApi,
    { tipo: TipoConsentimento; concedido: boolean }
  >({
    mutationFn: ({ tipo, concedido }) =>
      definirConsentimento(tipo, concedido, VERSAO_TEXTO_CONSENTIMENTO),
    onSuccess: (atualizado) => {
      queryClient.setQueryData<ConsentimentoResponse[]>(chavesPerfil.consentimentos, (atual) =>
        atual?.map((item) => (item.tipo === atualizado.tipo ? atualizado : item)),
      );
    },
    throwOnError: false,
  });
}

export function useExportarDados() {
  return useMutation<Record<string, unknown>, ErroApi, void>({
    mutationFn: exportarMeusDados,
    throwOnError: false,
  });
}

/** Exclusão de conta. Quem chama já passou pela dupla confirmação e pela senha. */
export function useExcluirConta() {
  return useMutation<void, ErroApi, { senha: string }>({
    mutationFn: ({ senha }) => excluirMinhaConta(senha),
    throwOnError: false,
  });
}
