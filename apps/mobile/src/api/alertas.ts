import { seg } from './caminho';
import { cliente } from './cliente';
import type { AlertaResponse, PaginaResponse } from './tipos';
import { alertaResponseSchema, paginaSchema } from '@/schemas';
import { validarEmDev } from '@/schemas/validar';

export async function listarAlertas(
  pagina: number,
  apenasNaoLidos = false,
  tamanho = 20,
): Promise<PaginaResponse<AlertaResponse>> {
  const { data } = await cliente.get<PaginaResponse<AlertaResponse>>('/alertas', {
    params: { pagina, tamanho, apenasNaoLidos },
  });
  return validarEmDev(paginaSchema(alertaResponseSchema), data, 'GET /alertas');
}

/**
 * Contador do badge.
 *
 * Endpoint próprio, e não `totalElementos` da listagem: o número é pedido em telas que não mostram a
 * lista, e buscar a primeira página só para lê-lo traria vinte corpos de notificação a cada
 * verificação.
 */
export async function contarNaoLidos(): Promise<number> {
  const { data } = await cliente.get<{ naoLidos: number }>('/alertas/nao-lidos/contagem');
  return data.naoLidos;
}

/** Idempotente no servidor: marcar de novo devolve o mesmo estado, sem erro. */
export async function marcarAlertaLido(id: string): Promise<AlertaResponse> {
  const { data } = await cliente.patch<AlertaResponse>(`/alertas/${seg(id)}/lido`);
  return validarEmDev(alertaResponseSchema, data, `PATCH /alertas/${id}/lido`);
}
