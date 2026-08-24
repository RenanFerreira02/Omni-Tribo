import { cliente } from './cliente';
import type { UsuarioBuscaResponse } from './tipos';
import { usuarioBuscaResponseSchema } from '@/schemas';
import { validarEmDev } from '@/schemas/validar';

/**
 * Encontra um vizinho da MESMA tribo pelo `@` exato.
 *
 * <p>Não existe busca por prefixo, por parte do nome nem por similaridade, e não existe listagem de
 * membros: qualquer uma delas daria a quem está autenticado um mapa social do bairro — e, como a
 * transferência é restrita à mesma tribo, esse mapa seria uma lista de alvos. Ver ADR 0028.
 *
 * Quem pergunta é o dono do JWT: a identidade NUNCA vai na query.
 *
 * Handle inexistente, de outra tribo ou de conta inativa respondem o MESMO 404 — chegam aqui como
 * `naoEncontrado`, e a tela trata os três com a mesma frase, porque distingui-los recriaria no
 * cliente o oráculo que o servidor fechou.
 */
export async function buscarPorHandle(handle: string): Promise<UsuarioBuscaResponse> {
  const { data } = await cliente.get<UsuarioBuscaResponse>('/usuarios/busca', {
    params: { handle },
  });
  return validarEmDev(usuarioBuscaResponseSchema, data, 'GET /usuarios/busca');
}
