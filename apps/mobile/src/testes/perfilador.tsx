import { Profiler, type ProfilerOnRenderCallback, type ReactElement, type ReactNode } from 'react';

/**
 * Um commit do React, como o `<Profiler>` o entrega.
 *
 * `actualDuration` é o tempo gasto RENDERIZANDO neste commit — o número que cai quando um
 * componente deixa de re-renderizar à toa. `baseDuration` é o custo de renderizar a subárvore
 * inteira sem nenhuma memoização: ele NÃO cai com `React.memo`, e a distância entre os dois é a
 * economia. Guardar só um dos dois faria a medição parecer melhor ou pior do que é.
 */
export type Commit = {
  id: string;
  fase: 'mount' | 'update' | 'nested-update';
  atualMs: number;
  baseMs: number;
};

export type Perfil = {
  /** Envolve a árvore a medir. Passe o resultado para `render`, que exige um ReactElement. */
  sonda: (filhos: ReactNode) => ReactElement;
  /** Todos os commits, na ordem. */
  commits: () => Commit[];
  /** Só os commits de ATUALIZAÇÃO — a montagem inicial custa caro e não é o que se compara. */
  atualizacoes: () => Commit[];
  /** Soma de `actualDuration` das atualizações. É o número do antes-e-depois. */
  custoDasAtualizacoesMs: () => number;
  /** Descarta o que foi gravado até aqui — para separar montagem de interação. */
  zerar: () => void;
  /** Linha pronta para colar na evidência. */
  resumo: () => string;
};

/**
 * Perfilador de renderização, sem dependência nova.
 *
 * <p>O app não tinha ferramental de perfilamento nenhum — nem `reassure`, nem `react-devtools`, nem
 * teste de contagem de render. O `<Profiler>` resolve isso com o que o React já exporta: ele é API
 * estável e vem no build de desenvolvimento, que é o que o jest executa.
 *
 * <p><b>O que ele mede, e o que não mede.</b> Mede tempo de RENDER por commit, na máquina que roda o
 * teste, com o renderer de teste. Não mede o custo de layout, de ponte nativa nem de aparelho real —
 * um número daqui NÃO é latência percebida no Expo Go, e não deve ser apresentado como se fosse. O
 * que ele responde com precisão é a pergunta comparativa: "esta interação passou a renderizar
 * menos?". Por isso toda leitura útil é uma razão entre duas medições do MESMO teste, nunca um valor
 * absoluto isolado.
 *
 * <p>Use `zerar()` depois do `render` inicial: a montagem domina qualquer soma e esconderia
 * exatamente a economia que se quer enxergar na interação seguinte.
 */
export function novoPerfil(id: string): Perfil {
  const commits: Commit[] = [];

  const aoRenderizar: ProfilerOnRenderCallback = (idDoCommit, fase, duracaoAtual, duracaoBase) => {
    commits.push({
      id: String(idDoCommit),
      fase: fase as Commit['fase'],
      atualMs: duracaoAtual,
      baseMs: duracaoBase,
    });
  };

  const atualizacoes = () => commits.filter((c) => c.fase !== 'mount');

  return {
    sonda: (filhos: ReactNode) => (
      <Profiler id={id} onRender={aoRenderizar}>
        {filhos}
      </Profiler>
    ),
    commits: () => [...commits],
    atualizacoes,
    custoDasAtualizacoesMs: () => atualizacoes().reduce((soma, c) => soma + c.atualMs, 0),
    zerar: () => {
      commits.length = 0;
    },
    resumo: () => {
      const atu = atualizacoes();
      const total = atu.reduce((soma, c) => soma + c.atualMs, 0);
      return `${id}: ${atu.length} commits de atualização, ${total.toFixed(2)} ms de render`;
    },
  };
}
