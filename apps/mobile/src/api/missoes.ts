import { seg } from './caminho';
import { cliente } from './cliente';
import type {
  CriarMissaoRequest,
  FiltroMissoes,
  FiltroProximas,
  MissaoProximaResponse,
  MissaoResponse,
  PaginaResponse,
  PreviaRecompensaResponse,
  RegistrarCheckinRequest,
} from './tipos';
import {
  missaoProximaResponseSchema,
  missaoResponseSchema,
  paginaSchema,
  previaRecompensaResponseSchema,
} from '@/schemas';
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
  const { data } = await cliente.get<MissaoResponse>(`/missoes/${seg(id)}`);
  return validarEmDev(missaoResponseSchema, data, `GET /missoes/${id}`);
}

/**
 * Cria a missão. Ela nasce em RASCUNHO — publicar é uma ação separada.
 *
 * O corpo NÃO carrega recompensa: o servidor calcula e congela na criação (ADR 0009). Ver
 * `CriarMissaoRequest`.
 */
export async function criarMissao(corpo: CriarMissaoRequest): Promise<MissaoResponse> {
  const { data } = await cliente.post<MissaoResponse>('/missoes', corpo);
  return validarEmDev(missaoResponseSchema, data, 'POST /missoes');
}

/**
 * Prévia da recompensa, sem criar nada.
 *
 * Recebe o MESMO corpo da criação — inclusive as seis regras cruzadas —, e é a única forma
 * autorizada de o app mostrar XP e tokens antes de publicar. **A fórmula não é reimplementada em
 * TypeScript**: duas fontes de verdade divergem no primeiro ajuste de parâmetro do servidor, e o
 * usuário veria um número na criação e outro na missão publicada.
 */
export async function previaRecompensa(
  corpo: CriarMissaoRequest,
): Promise<PreviaRecompensaResponse> {
  const { data } = await cliente.post<PreviaRecompensaResponse>(
    '/missoes/previa-recompensa',
    corpo,
  );
  return validarEmDev(previaRecompensaResponseSchema, data, 'POST /missoes/previa-recompensa');
}

export type AcaoMissao =
  'publicar' | 'aceitar' | 'iniciar' | 'desistir' | 'cancelar' | 'contestar' | 'confirmar';

export async function aplicarAcao(
  id: string,
  acao: AcaoMissao,
  motivo?: string,
): Promise<MissaoResponse> {
  const corpo = motivo ? { motivo } : undefined;
  const { data } = await cliente.post<MissaoResponse>(`/missoes/${seg(id)}/${seg(acao)}`, corpo);
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
  const { data } = await cliente.post<MissaoResponse>(`/missoes/${seg(id)}/checkin`, corpo, {
    headers: { 'Idempotency-Key': chaveIdempotencia },
  });
  return validarEmDev(missaoResponseSchema, data, `POST /missoes/${id}/checkin`);
}
