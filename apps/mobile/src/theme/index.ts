import type { CategoriaMissao } from '@/api/tipos';

import { coresCategoria as mapaCategoria } from './tokens';

export { cores, espaco, raio, tipografia } from './tokens';
export type { NomeCor } from './tokens';

export interface ParCorCategoria {
  fundo: string;
  texto: string;
}

/**
 * Reexporta o mapa de categoria com o tipo do domínio.
 *
 * A anotação é o ponto: se `CategoriaMissao` ganhar um valor no backend e ninguém acrescentar a cor
 * correspondente, isto deixa de compilar. Sem ela, a categoria nova chegaria à tela como
 * `undefined` em tempo de execução e quebraria o card — um erro de runtime no lugar de um erro de
 * typecheck. `tokens.ts` continua sem importar nada de `src/api`.
 */
export const coresCategoria: Record<CategoriaMissao, ParCorCategoria> = mapaCategoria;
