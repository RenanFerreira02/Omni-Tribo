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
 * Erros de um `safeParse` do Zod, no MESMO formato de `errosPorCampo`.
 *
 * Existe para que a tela trate validação local e validação do servidor com um só tipo de estado.
 * Sem isto, formulários que não usam react-hook-form acabam validando à mão — foi o que aconteceu
 * com a transferência de tokens, onde a checagem manual deixava passar destinatário vazio e
 * quantidade acima do teto, e devolvia um `return` mudo para campo vazio.
 *
 * Fica com a PRIMEIRA mensagem de cada campo: exibir duas linhas de erro sob o mesmo input é
 * ruído, e a primeira regra violada costuma ser a que o usuário precisa corrigir antes.
 */
export function errosDoZod(erro: { issues: readonly { path: PropertyKey[]; message: string }[] }) {
  const mapa: Record<string, string> = {};
  for (const problema of erro.issues) {
    const campo = String(problema.path[0] ?? '');
    if (campo && !(campo in mapa)) mapa[campo] = problema.message;
  }
  return mapa;
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
