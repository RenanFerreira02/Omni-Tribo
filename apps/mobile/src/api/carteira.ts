import { cliente } from './cliente';
import type { CarteiraResponse, LancamentoResponse, PaginaResponse } from './tipos';
import { carteiraResponseSchema, lancamentoResponseSchema, paginaSchema } from '@/schemas';
import { validarEmDev } from '@/schemas/validar';

export async function buscarCarteira(): Promise<CarteiraResponse> {
  // Sem id no path: a carteira é sempre a do usuário do JWT. Identidade NUNCA vem do cliente.
  const { data } = await cliente.get<CarteiraResponse>('/carteira');
  return validarEmDev(carteiraResponseSchema, data, 'GET /carteira');
}

export async function listarLancamentos(
  pagina: number,
  tamanho = 20,
): Promise<PaginaResponse<LancamentoResponse>> {
  const { data } = await cliente.get<PaginaResponse<LancamentoResponse>>('/carteira/lancamentos', {
    params: { pagina, tamanho },
  });
  return validarEmDev(paginaSchema(lancamentoResponseSchema), data, 'GET /carteira/lancamentos');
}

export interface RespostaSaque {
  protocolo: string;
  saldoBrlRestante: number;
  replay: boolean;
}

/**
 * Saque. Hoje responde 422 com `type` `saque-desabilitado` — está desligado por configuração, não
 * quebrado (ADR 0009). A função existe porque a mecânica é a que a conversão patrocinada de TOKEN
 * vai reaproveitar, e porque a tela precisa exercitar o erro tipado.
 */
export async function sacar(valorBrl: number, chaveIdempotencia: string): Promise<RespostaSaque> {
  const { data } = await cliente.post<RespostaSaque>(
    '/carteira/saques',
    { valorBrl },
    { headers: { 'Idempotency-Key': chaveIdempotencia } },
  );
  return data;
}
