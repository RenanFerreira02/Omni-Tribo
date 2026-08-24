import { cliente } from './cliente';
import type { BeneficioResponse, PaginaResponse, ResgateResponse } from './tipos';
import { beneficioResponseSchema, paginaSchema, resgateResponseSchema } from '@/schemas';
import { validarEmDev } from '@/schemas/validar';

/**
 * Recorte do catálogo. Proximidade OU tribo — o servidor recusa os dois juntos com 422.
 *
 * Não são combináveis por decisão do backend (ADR 0027): dois critérios de pertencimento sobre o
 * mesmo conjunto dariam um resultado indistinguível do mais restritivo, sem que ninguém soubesse
 * qual dos dois recortou.
 */
export type FiltroBeneficios =
  { lat: number; lon: number; raioMetros: number } | { triboId: string };

function porProximidade(
  filtro: FiltroBeneficios,
): filtro is { lat: number; lon: number; raioMetros: number } {
  return 'lat' in filtro;
}

export async function listarBeneficios(
  filtro: FiltroBeneficios,
  pagina = 0,
  tamanho = 20,
): Promise<PaginaResponse<BeneficioResponse>> {
  const params = porProximidade(filtro)
    ? { lat: filtro.lat, lon: filtro.lon, raioMetros: filtro.raioMetros, pagina, tamanho }
    : { triboId: filtro.triboId, pagina, tamanho };

  const { data } = await cliente.get<PaginaResponse<BeneficioResponse>>('/beneficios', { params });
  return validarEmDev(paginaSchema(beneficioResponseSchema), data, 'GET /beneficios');
}

/**
 * Resgate: QUEIMA tokens da carteira em troca de um benefício.
 *
 * O CUSTO não vai no corpo — é lido do catálogo pelo servidor e congelado na linha de resgate.
 * Mandar um preço daqui deixaria o cliente escolher quanto paga.
 *
 * `Idempotency-Key` é obrigatória, e num sumidouro ela protege o USUÁRIO: um retry de rede sem
 * chave queimaria duas vezes o saldo que ele gastou uma vez só. Vem de FORA, gerada junto com a
 * intenção — ver `novaChaveIdempotencia` em `src/lib/ids.ts`.
 */
export async function resgatarBeneficio(
  beneficioId: string,
  chaveIdempotencia: string,
): Promise<ResgateResponse> {
  const { data } = await cliente.post<ResgateResponse>(
    '/resgates',
    { beneficioId },
    { headers: { 'Idempotency-Key': chaveIdempotencia } },
  );
  return validarEmDev(resgateResponseSchema, data, 'POST /resgates');
}
