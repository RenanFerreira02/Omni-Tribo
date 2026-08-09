import { cliente } from './cliente';
import type { ConsentimentoResponse, PerfilResponse, TipoConsentimento } from './tipos';
import { consentimentoResponseSchema, perfilResponseSchema } from '@/schemas';
import { validarEmDev } from '@/schemas/validar';
import { z } from 'zod';

/**
 * Perfil completo: nome, handle, tribo, XP, nível e conquistas.
 *
 * Não é `/auth/me`. Aquele continua sendo a checagem barata de identidade do boot, resolvida só dos
 * claims do JWT sem tocar o banco — este faz joins e só é chamado quando a tela de perfil abre.
 */
export async function buscarPerfil(): Promise<PerfilResponse> {
  const { data } = await cliente.get<PerfilResponse>('/usuarios/me');
  return validarEmDev(perfilResponseSchema, data, 'GET /usuarios/me');
}

export async function listarConsentimentos(): Promise<ConsentimentoResponse[]> {
  const { data } = await cliente.get<ConsentimentoResponse[]>('/usuarios/me/consentimentos');
  return validarEmDev(
    z.array(consentimentoResponseSchema),
    data,
    'GET /usuarios/me/consentimentos',
  );
}

/**
 * Registra uma escolha de consentimento.
 *
 * `versaoTexto` é a versão que o usuário REALMENTE viu na tela, e por isso viaja do cliente: se o
 * servidor a preenchesse, um deploy no meio faria o registro afirmar que a pessoa concordou com um
 * texto que nunca leu.
 */
export async function definirConsentimento(
  tipo: TipoConsentimento,
  concedido: boolean,
  versaoTexto: string,
): Promise<ConsentimentoResponse> {
  const { data } = await cliente.put<ConsentimentoResponse>(`/usuarios/me/consentimentos/${tipo}`, {
    concedido,
    versaoTexto,
  });
  return validarEmDev(consentimentoResponseSchema, data, `PUT /usuarios/me/consentimentos/${tipo}`);
}

/**
 * Exportação de dados pessoais (LGPD art. 18, V).
 *
 * Sem schema de validação: o formato é um mapa de seções que CRESCE quando um módulo novo do
 * backend passa a contribuir, e um schema estrito aqui gritaria contra exatamente o comportamento
 * desejado. O app não interpreta o conteúdo — ele o entrega ao usuário.
 */
export async function exportarMeusDados(): Promise<Record<string, unknown>> {
  const { data } = await cliente.get<Record<string, unknown>>('/usuarios/me/dados');
  return data;
}

/**
 * Exclusão de conta (LGPD art. 18, VI). IRREVERSÍVEL.
 *
 * A senha atual vai no corpo porque o access token vive 15 minutos e sobrevive ao aparelho trocar
 * de mãos. A dupla confirmação na tela cobre o toque acidental; isto cobre a pessoa errada.
 */
export async function excluirMinhaConta(senha: string): Promise<void> {
  await cliente.delete('/usuarios/me', { data: { senha } });
}
