import { cliente } from './cliente';
import type { ImpactoResponse } from './tipos';
import { impactoResponseSchema } from '@/schemas';
import { validarEmDev } from '@/schemas/validar';

/**
 * O painel de impacto. Exige ADMIN — usuário comum recebe 403, e a tela trata isso pelo `type`.
 *
 * Sem parâmetro nenhum de propósito: o recorte é o sistema inteiro. Um filtro por período seria
 * útil e não existe no backend; inventá-lo aqui produziria número que o servidor não calculou.
 */
export async function buscarImpacto(): Promise<ImpactoResponse> {
  const { data } = await cliente.get<ImpactoResponse>('/admin/impacto');
  return validarEmDev(impactoResponseSchema, data, 'GET /admin/impacto');
}
