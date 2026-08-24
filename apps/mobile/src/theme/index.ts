import type { CategoriaMissao, StatusMissao } from '@/api/tipos';

import {
  coresCategoria as mapaCategoria,
  coresStatus as mapaStatus,
  glifoCategoria as mapaGlifo,
} from './tokens';

export { alvo, cores, espaco, glifo, raio, textoAcessivel, tipografia, traco } from './tokens';
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

/** Mesma disciplina de `coresCategoria`: status novo sem cor não compila. */
export const coresStatus: Record<StatusMissao, ParCorCategoria> = mapaStatus;

/** Mesma disciplina: categoria nova sem glifo não compila — e a cor deixaria de ter dupla. */
export const glifoCategoria: Record<CategoriaMissao, string> = mapaGlifo;
