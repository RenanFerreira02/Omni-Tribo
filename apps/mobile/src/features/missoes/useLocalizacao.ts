import * as Location from 'expo-location';
import { useCallback, useEffect, useState } from 'react';

export interface Coordenada {
  lat: number;
  lon: number;
  acuraciaM: number;
  /** O que o SISTEMA reporta. Vai como está para o servidor, que decide o que fazer com isso. */
  mocked: boolean;
}

type Estado = 'inicial' | 'carregando' | 'concedida' | 'negada' | 'erro';

/**
 * Localização do dispositivo.
 *
 * O app NÃO julga a coordenada: não compara com o raio da missão, não decide se está perto o
 * bastante, não filtra por acurácia. Tudo isso é validado no servidor, e valor calculado no cliente
 * é ignorado por princípio. Aqui só se lê o sensor e se repassa o que ele disse — inclusive
 * `mocked`, que é auto-reportado e vale exatamente o que um cliente hostil quiser que valha. É por
 * isso que ele é um sinal, não uma defesa (ver docs/seguranca/antifraude-geolocalizacao.md).
 */
/**
 * <b>O padrão é NÃO pedir ao montar, e a inversão foi deliberada.</b>
 *
 * Antes o default era `true`, e bastava uma tela chamar `useLocalizacao()` sem argumento para o
 * diálogo do sistema disparar na montagem — sem nenhuma justificativa antes. Foi o que aconteceu
 * com a aba de missões, que é a primeira a montar: ela gastava a única chance de explicar, e o card
 * do mapa chegava tarde demais.
 *
 * Com o default invertido, o caminho perigoso passou a exigir opt-in explícito. Quem escrever
 * `useLocalizacao()` numa tela nova recebe o comportamento seguro; pedir sem explicar virou uma
 * decisão que aparece no diff.
 */
export function useLocalizacao(pedirAoMontar = false) {
  const [coordenada, setCoordenada] = useState<Coordenada | null>(null);
  // O estado inicial já nasce 'carregando' quando há leitura automática. É o que permite ao efeito
  // de montagem chamar `obter()` sem nenhum setState síncrono — a renderização em cascata que
  // `react-hooks/set-state-in-effect` sinaliza, com razão.
  const [estado, setEstado] = useState<Estado>(pedirAoMontar ? 'carregando' : 'inicial');

  const obter = useCallback(async () => {
    try {
      const permissao = await Location.requestForegroundPermissionsAsync();
      if (!permissao.granted) {
        setEstado('negada');
        return null;
      }

      const posicao = await Location.getCurrentPositionAsync({
        accuracy: Location.Accuracy.High,
      });

      const lida: Coordenada = {
        lat: posicao.coords.latitude,
        lon: posicao.coords.longitude,
        // O sensor pode não informar acurácia. 9999 é honesto e o servidor rejeita — melhor que
        // fingir um valor bom, que produziria um check-in aceito sem base para afirmá-lo.
        acuraciaM: posicao.coords.accuracy ?? 9999,
        mocked: posicao.mocked ?? false,
      };
      setCoordenada(lida);
      setEstado('concedida');
      return lida;
    } catch {
      setEstado('erro');
      return null;
    }
  }, []);

  /** Nova leitura a pedido do usuário: aqui o `carregando` é anunciado, porque houve um toque. */
  const recarregar = useCallback(() => {
    setEstado('carregando');
    return obter();
  }, [obter]);

  useEffect(() => {
    // A regra abaixo sinaliza qualquer chamada que TRANSITIVAMENTE faça setState dentro de um
    // efeito, e aqui ela erra o alvo: ler o sensor de localização é o caso de "sincronizar com um
    // sistema externo" que a própria documentação do React admite, e nenhum `setEstado` acontece
    // de forma síncrona — todos ocorrem depois de um `await` da API do sistema, num callback. Não
    // há cascata de renderização a evitar; há uma leitura assíncrona cujo resultado, quando chega,
    // vira estado.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (pedirAoMontar) void obter();
  }, [pedirAoMontar, obter]);

  return { coordenada, estado, obter, recarregar };
}
