import type { z } from 'zod';

/**
 * Confere a resposta contra o schema e DEVOLVE O DADO de qualquer jeito.
 *
 * A assimetria é deliberada. Um contrato que derivou não é motivo para derrubar a tela na cara do
 * usuário: na maioria das vezes o campo novo (ou o nulo inesperado) não é o que aquela tela usa, e
 * um `parse()` estrito transformaria uma divergência inofensiva numa falha total. O que precisamos é
 * SABER que derivou — daí o log em desenvolvimento, com o caminho exato do campo.
 *
 * Em produção não valida: o custo de percorrer cada item de uma lista paginada não se paga quando
 * ninguém está lendo o console.
 *
 * Não confundir com a validação de FORMULÁRIO, em `src/schemas/index.ts`, que é estrita de
 * propósito — ali o dado é do usuário e recusá-lo é o comportamento certo.
 */
export function validarEmDev<T>(schema: z.ZodType<T>, dado: unknown, contexto: string): T {
  if (__DEV__) {
    const resultado = schema.safeParse(dado);
    if (!resultado.success) {
      const divergencias = resultado.error.issues
        .map((problema) => `  ${problema.path.join('.') || '(raiz)'}: ${problema.message}`)
        .join('\n');

      console.warn(
        `[contrato] resposta de ${contexto} divergiu do schema:\n${divergencias}\n` +
          'O dado foi usado assim mesmo. Se a tela quebrar, é aqui que começa a investigação.',
      );
    }
  }
  return dado as T;
}
