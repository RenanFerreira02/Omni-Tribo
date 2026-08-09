import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Devolve `valor` só depois de ele ficar parado por `atrasoMs`.
 *
 * Usado em três lugares com atrasos diferentes, e os números não são arbitrários:
 * - **500 ms** ao arrastar o mapa — recarregar a cada `moveend` dispararia uma consulta geoespacial
 *   por gesto, e arrastar o mapa é um gesto contínuo;
 * - **500 ms** no CEP — sem isso é uma chamada externa por tecla digitada;
 * - **400 ms** na prévia de recompensa — mais curto porque o número precisa parecer que responde ao
 *   formulário, e a chamada é interna.
 *
 * O timer é limpo no cleanup, então desmontar a tela no meio de uma digitação não deixa um
 * `setState` pendente para uma árvore que já não existe.
 */
export function useDebounce<T>(valor: T, atrasoMs: number): T {
  const [atrasado, setAtrasado] = useState(valor);

  useEffect(() => {
    const timer = setTimeout(() => setAtrasado(valor), atrasoMs);
    return () => clearTimeout(timer);
  }, [valor, atrasoMs]);

  return atrasado;
}

/**
 * Versão para CALLBACK, quando o gatilho é um evento e não um valor de estado.
 *
 * É o caso do mapa: `onRegionChange` chega da WebView como evento, e guardá-lo em estado só para
 * poder aplicar `useDebounce` provocaria uma renderização por movimento — exatamente o que o
 * debounce existe para evitar.
 *
 * A função devolvida é estável entre renderizações e sempre chama a versão MAIS RECENTE de
 * `callback`, via ref. Sem a ref, a closure capturaria o callback da primeira renderização e a
 * consulta usaria filtros velhos.
 */
export function useCallbackComDebounce<A extends unknown[]>(
  callback: (...args: A) => void,
  atrasoMs: number,
): (...args: A) => void {
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const maisRecente = useRef(callback);

  useEffect(() => {
    maisRecente.current = callback;
  }, [callback]);

  useEffect(() => {
    return () => {
      if (timer.current) clearTimeout(timer.current);
    };
  }, []);

  // `useCallback`, e não `useRef(fn).current`: ler `.current` na renderização viola
  // `react-hooks/refs`. A identidade continua estável enquanto o atraso não mudar.
  return useCallback(
    (...args: A) => {
      if (timer.current) clearTimeout(timer.current);
      timer.current = setTimeout(() => maisRecente.current(...args), atrasoMs);
    },
    [atrasoMs],
  );
}
