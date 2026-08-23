import { useEffect, useState } from 'react';
import { AccessibilityInfo } from 'react-native';

/**
 * "Reduzir movimento" do sistema, observado.
 *
 * O app animava sem perguntar: o `Esqueleto` pulsa em laço INFINITO enquanto qualquer tela carrega,
 * e a `FolhaInferior` desliza a cada abertura. Movimento persistente é o caso que a preferência do
 * sistema existe para atender — vestibular, enxaqueca, transtorno de atenção —, e o pulso do
 * esqueleto é justamente movimento persistente, não uma transição que passa.
 *
 * **Com listener, e não só a leitura inicial.** A preferência é alterável com o app aberto, e ler
 * uma vez na montagem deixaria a tela animando até alguém reiniciar. O `remove()` no cleanup é o que
 * evita o vazamento em cada tela que monta e desmonta.
 *
 * Começa em `false` porque é o que a maioria dos aparelhos reporta, e porque animar por engano
 * durante um quadro é menos ruim que não animar por engano para todo mundo.
 */
export function useMovimentoReduzido(): boolean {
  const [reduzido, setReduzido] = useState(false);

  useEffect(() => {
    let ativo = true;

    void AccessibilityInfo.isReduceMotionEnabled().then((valor) => {
      if (ativo) setReduzido(valor);
    });

    const inscricao = AccessibilityInfo.addEventListener('reduceMotionChanged', setReduzido);

    return () => {
      ativo = false;
      inscricao.remove();
    };
  }, []);

  return reduzido;
}
