import { useEffect, useState } from 'react';

import {
  gravarApresentacaoRadar,
  lerApresentacaoRadar,
  type ApresentacaoRadar,
} from './apresentacao';

/**
 * A apresentação escolhida, restaurada na montagem e gravada a cada troca.
 *
 * A leitura é assíncrona (keystore), então a tela monta com o default e assume o valor guardado no
 * quadro seguinte. Assumir o contrário — segurar a renderização até a leitura chegar — trocaria um
 * quadro de mapa por uma tela em branco, que é pior para todo mundo.
 *
 * A gravação é disparada e NÃO aguardada: a troca de apresentação já é visível em memória, e travar
 * o toque esperando o keystore faria o alternador parecer lento sem nenhum ganho.
 */
export function useApresentacaoRadar(): [ApresentacaoRadar, (valor: ApresentacaoRadar) => void] {
  const [apresentacao, setApresentacao] = useState<ApresentacaoRadar>('mapa');

  useEffect(() => {
    let ativo = true;
    void lerApresentacaoRadar().then((guardada) => {
      if (ativo) setApresentacao(guardada);
    });
    return () => {
      ativo = false;
    };
  }, []);

  function trocar(valor: ApresentacaoRadar) {
    setApresentacao(valor);
    void gravarApresentacaoRadar(valor);
  }

  return [apresentacao, trocar];
}
