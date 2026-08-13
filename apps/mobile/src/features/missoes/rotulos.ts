import type { CategoriaMissao, ComplexidadeMissao } from '@/api/tipos';

/**
 * Nomes de exibição dos enums de missão.
 *
 * Um lugar só. `ROTULO_COMPLEXIDADE` vivia dentro de `app/missao/criar.tsx`, e a tela de detalhe
 * precisou do mesmo mapa para explicar de onde vem a recompensa — copiar produziria duas listas que
 * divergem no dia em que o backend ganhar um nível novo, e a divergência apareceria como duas telas
 * chamando a mesma coisa por nomes diferentes.
 *
 * `Record<Enum, string>` e não `Partial`: um valor novo no enum quebra o typecheck aqui, em vez de
 * cair como `undefined` na tela.
 */
export const ROTULO_COMPLEXIDADE: Record<ComplexidadeMissao, string> = {
  LEVE: 'Leve',
  MEDIA: 'Média',
  PESADA: 'Pesada',
};

export const COMPLEXIDADES: ComplexidadeMissao[] = ['LEVE', 'MEDIA', 'PESADA'];

export const CATEGORIAS: CategoriaMissao[] = ['ENTREGA', 'COLETA', 'TRIBO', 'AJUDA'];
