import { paraErroApi, type ErroApi } from '@/api/erros';

/**
 * Erros de campo vindos do backend, prontos para casar com o input.
 *
 * Só o 400 de validação traz `errors[{campo, mensagem}]` — os demais tipos não têm campo culpado, e
 * inventar um marcaria a caixa errada de vermelho. O `type` é o que decide isso; o status 400
 * sozinho não bastaria, porque corpo ilegível e media type errado também são 400 e não trazem lista.
 */
export function errosPorCampo(erro: unknown): Record<string, string> {
  if (erro === null || erro === undefined) return {};
  const api = paraErroApi(erro);
  if (api.tipo !== 'requisicaoInvalida') return {};
  return Object.fromEntries(api.erros.map((item) => [item.campo, item.mensagem]));
}

/**
 * Mensagem geral do formulário.
 *
 * Devolve null quando o erro já foi distribuído pelos campos: repetir "Um ou mais campos falharam
 * na validação" acima de três campos já marcados é ruído.
 */
export function mensagemDoErro(erro: unknown): string | null {
  if (erro === null || erro === undefined) return null;
  const api: ErroApi = paraErroApi(erro);
  if (api.tipo === 'requisicaoInvalida' && api.erros.length > 0) return null;
  return api.detail;
}
