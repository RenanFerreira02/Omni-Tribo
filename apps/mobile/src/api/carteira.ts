import { cliente } from './cliente';
import type {
  CarteiraResponse,
  LancamentoResponse,
  PaginaResponse,
  TransferenciaResponse,
} from './tipos';
import {
  carteiraResponseSchema,
  lancamentoResponseSchema,
  paginaSchema,
  transferenciaResponseSchema,
} from '@/schemas';
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

/**
 * Transferência de tokens para um membro da MESMA tribo.
 *
 * `Idempotency-Key` é obrigatória e tem no mínimo 8 caracteres — mesma regra do check-in, e pelo
 * mesmo motivo: um retry de rede não pode virar uma segunda transferência.
 *
 * O remetente NUNCA vai no corpo; sai do JWT. Tribo diferente e saldo insuficiente respondem os
 * dois **422 `regra-negocio-violada`**, então a tela distingue pelo `detail` — que é a única
 * exceção honesta à regra de nunca ler `detail`, e existe porque o backend ainda não deu URI
 * própria a essas duas causas.
 */
export async function transferirTokens(
  destinatarioId: string,
  tokens: number,
  chaveIdempotencia: string,
  mensagem?: string,
): Promise<TransferenciaResponse> {
  const { data } = await cliente.post<TransferenciaResponse>(
    '/carteira/transferencias',
    { destinatarioId, tokens, mensagem },
    { headers: { 'Idempotency-Key': chaveIdempotencia } },
  );
  return validarEmDev(transferenciaResponseSchema, data, 'POST /carteira/transferencias');
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
