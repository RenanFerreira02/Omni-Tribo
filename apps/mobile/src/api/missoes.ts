import { cliente } from './cliente';
import type {
  FiltroMissoes,
  FiltroProximas,
  MissaoProximaResponse,
  MissaoResponse,
  PaginaResponse,
  RegistrarCheckinRequest,
} from './tipos';
import { missaoProximaResponseSchema, missaoResponseSchema, paginaSchema } from '@/schemas';
import { validarEmDev } from '@/schemas/validar';
import { z } from 'zod';

export async function listarMissoes(
  filtro: FiltroMissoes,
): Promise<PaginaResponse<MissaoResponse>> {
  const { data } = await cliente.get<PaginaResponse<MissaoResponse>>('/missoes', {
    params: filtro,
  });
  return validarEmDev(paginaSchema(missaoResponseSchema), data, 'GET /missoes');
}

/**
 * Radar. Devolve ARRAY, não página — o backend limita por `limite` (máx. 100) e ordena por
 * distância crescente. Só missões `ABERTA`.
 */
export async function missoesProximas(filtro: FiltroProximas): Promise<MissaoProximaResponse[]> {
  const { data } = await cliente.get<MissaoProximaResponse[]>('/missoes/proximas', {
    params: filtro,
  });
  return validarEmDev(z.array(missaoProximaResponseSchema), data, 'GET /missoes/proximas');
}

export async function buscarMissao(id: string): Promise<MissaoResponse> {
  const { data } = await cliente.get<MissaoResponse>(`/missoes/${id}`);
  return validarEmDev(missaoResponseSchema, data, `GET /missoes/${id}`);
}

export type AcaoMissao =
  'publicar' | 'aceitar' | 'iniciar' | 'desistir' | 'cancelar' | 'contestar' | 'confirmar';

export async function aplicarAcao(
  id: string,
  acao: AcaoMissao,
  motivo?: string,
): Promise<MissaoResponse> {
  const corpo = motivo ? { motivo } : undefined;
  const { data } = await cliente.post<MissaoResponse>(`/missoes/${id}/${acao}`, corpo);
  return validarEmDev(missaoResponseSchema, data, `POST /missoes/${id}/${acao}`);
}

/**
 * Check-in geolocalizado.
 *
 * A `chaveIdempotencia` é obrigatória e vem de FORA: quem a gera é a tela, junto com a intenção do
 * usuário, para que um retry de rede repita a mesma chave e o servidor devolva o mesmo resultado em
 * vez de gravar um segundo check-in. Ver `src/lib/ids.ts`.
 *
 * As coordenadas enviadas são o que o dispositivo diz; a régua é do servidor. `distanciaM` nunca é
 * calculada aqui.
 */
export async function registrarCheckin(
  id: string,
  corpo: RegistrarCheckinRequest,
  chaveIdempotencia: string,
): Promise<MissaoResponse> {
  const { data } = await cliente.post<MissaoResponse>(`/missoes/${id}/checkin`, corpo, {
    headers: { 'Idempotency-Key': chaveIdempotencia },
  });
  return validarEmDev(missaoResponseSchema, data, `POST /missoes/${id}/checkin`);
}
