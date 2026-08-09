import { cliente } from './cliente';
import type {
  ClimaResponse,
  EnderecoResponse,
  PontoCustodiaResponse,
  TriboResponse,
} from './tipos';
import {
  climaResponseSchema,
  enderecoResponseSchema,
  pontoCustodiaResponseSchema,
  triboResponseSchema,
} from '@/schemas';
import { validarEmDev } from '@/schemas/validar';
import { z } from 'zod';

/** Tribos, pontos de custódia, clima e CEP — tudo que responde "onde". */

export async function listarTribos(): Promise<TriboResponse[]> {
  const { data } = await cliente.get<TriboResponse[]>('/tribos');
  return validarEmDev(z.array(triboResponseSchema), data, 'GET /tribos');
}

/** Só o detalhe traz o centro geográfico; a lista o omite para não virar N+1 no servidor. */
export async function buscarTribo(id: string): Promise<TriboResponse> {
  const { data } = await cliente.get<TriboResponse>(`/tribos/${id}`);
  return validarEmDev(triboResponseSchema, data, `GET /tribos/${id}`);
}

export async function buscarPontoCustodia(id: string): Promise<PontoCustodiaResponse> {
  const { data } = await cliente.get<PontoCustodiaResponse>(`/pontos-custodia/${id}`);
  return validarEmDev(pontoCustodiaResponseSchema, data, `GET /pontos-custodia/${id}`);
}

export async function pontosCustodiaProximos(
  lat: number,
  lon: number,
  raioMetros = 2000,
): Promise<PontoCustodiaResponse[]> {
  const { data } = await cliente.get<PontoCustodiaResponse[]>('/pontos-custodia', {
    params: { lat, lon, raioMetros },
  });
  return validarEmDev(z.array(pontoCustodiaResponseSchema), data, 'GET /pontos-custodia');
}

/**
 * Clima do card do mapa.
 *
 * Provedor fora do ar responde **503** com `type` `servico-externo-indisponivel`. O tratamento é
 * ESCONDER o card — quem chama trata pelo `tipo` do erro, nunca exibindo "erro inesperado" por uma
 * degradação prevista.
 */
export async function buscarClima(lat: number, lon: number): Promise<ClimaResponse> {
  const { data } = await cliente.get<ClimaResponse>('/clima', { params: { lat, lon } });
  return validarEmDev(climaResponseSchema, data, 'GET /clima');
}

/**
 * CEP → endereço, para preencher o formulário de missão.
 *
 * Conveniência, não fonte de verdade: o endereço que vale é o que o usuário confirmou e que viaja
 * no corpo da criação. CEP inexistente é **404**; provedor fora do ar é **503** — e as duas coisas
 * pedem reações opostas do usuário.
 */
export async function buscarEnderecoPorCep(cep: string): Promise<EnderecoResponse> {
  const { data } = await cliente.get<EnderecoResponse>(`/enderecos/${cep}`);
  return validarEmDev(enderecoResponseSchema, data, `GET /enderecos/${cep}`);
}
