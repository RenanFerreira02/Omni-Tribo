import axios from 'axios';

import { PREFIXO_API, resolverBaseUrl } from './baseUrl';
import { cliente } from './cliente';
import type { LoginResponse, MeResponse } from './tipos';
import { loginResponseSchema, meResponseSchema } from '@/schemas';
import { validarEmDev } from '@/schemas/validar';

export async function login(email: string, senha: string): Promise<LoginResponse> {
  const { data } = await cliente.post<LoginResponse>('/auth/login', { email, senha });
  return validarEmDev(loginResponseSchema, data, 'POST /auth/login');
}

export interface DadosRegistro {
  nome: string;
  email: string;
  handle: string;
  senha: string;
  triboId?: string;
}

export async function registrar(dados: DadosRegistro): Promise<LoginResponse> {
  const { data } = await cliente.post<LoginResponse>('/auth/registrar', dados);
  return validarEmDev(loginResponseSchema, data, 'POST /auth/registrar');
}

export async function me(): Promise<MeResponse> {
  const { data } = await cliente.get<MeResponse>('/auth/me');
  return validarEmDev(meResponseSchema, data, 'GET /auth/me');
}

export async function logout(refreshToken: string): Promise<void> {
  await cliente.post('/auth/logout', { refreshToken });
}

/**
 * Rotação usada na RESTAURAÇÃO DE BOOT, fora do interceptor.
 *
 * Instância limpa pelo mesmo motivo do refresh do interceptor: passar por `cliente` faria um 401
 * daqui disparar o próprio tratamento de 401, e o boot entraria num ciclo tentando consertar a
 * sessão que acabou de descobrir estar morta.
 */
export async function refresh(refreshToken: string): Promise<LoginResponse> {
  const { data } = await axios.post<LoginResponse>(
    `${resolverBaseUrl()}${PREFIXO_API}/auth/refresh`,
    { refreshToken },
    { headers: { 'Content-Type': 'application/json' }, timeout: 15000 },
  );
  return validarEmDev(loginResponseSchema, data, 'POST /auth/refresh');
}
