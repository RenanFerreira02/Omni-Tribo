import { act, screen } from '@testing-library/react-native';

import { Mapa } from '@/components/Mapa';
import { chaveGoogleMaps } from '@/lib/chaveMapa';
import { render } from '@/testes/render';

jest.mock('@/lib/chaveMapa', () => ({ chaveGoogleMaps: jest.fn() }));

/**
 * A fachada do mapa: qual motor desenha, e que a troca para o Leaflet acontece SOZINHA quando o
 * Google falha — é o que impede o mapa de ficar cinza na frente de alguém.
 *
 * A WebView é dublê (`jest.setup.ts`): ela repassa as props para uma View, então o HTML, a origem e
 * o `onMessage` são inspecionáveis. Renderização real do mapa é verificação manual.
 */
const chave = jest.mocked(chaveGoogleMaps);

const MARCADORES = [
  { id: 'missao:1', lat: -23.54, lon: -46.73, cor: 'verde', forma: 'pino' as const, rotulo: 'M1' },
];

function webview() {
  return screen.getByTestId('webview-mapa');
}

function html(): string {
  return webview().props.source.html as string;
}

async function avisar(payload: Record<string, unknown>) {
  await act(async () => {
    webview().props.onMessage({ nativeEvent: { data: JSON.stringify(payload) } });
  });
}

describe('Mapa', () => {
  it('sem chave, desenha com o Leaflet e sem origem declarada', async () => {
    chave.mockReturnValue(null);
    await render(<Mapa centro={{ lat: -23.54, lon: -46.73 }} marcadores={MARCADORES} />);

    expect(html()).toContain('leaflet');
    expect(html()).not.toContain('maps.googleapis.com');
    expect(webview().props.source.baseUrl).toBeUndefined();
  });

  it('com chave, desenha com o Google, com a chave na URL e a origem que permite restringi-la', async () => {
    chave.mockReturnValue('CHAVE-DE-TESTE');
    await render(<Mapa centro={{ lat: -23.54, lon: -46.73 }} marcadores={MARCADORES} />);

    expect(html()).toContain('maps.googleapis.com/maps/api/js?key=CHAVE-DE-TESTE');
    expect(webview().props.source.baseUrl).toBe('https://mapa.omnitribo.app/');
  });

  it('quando o Google avisa falha, troca para o Leaflet sem intervenção', async () => {
    chave.mockReturnValue('CHAVE-RECUSADA');
    await render(<Mapa centro={{ lat: -23.54, lon: -46.73 }} marcadores={MARCADORES} />);
    expect(html()).toContain('maps.googleapis.com');

    await avisar({ tipo: 'falha', motivo: 'autenticacao' });

    expect(html()).toContain('leaflet');
    expect(html()).not.toContain('maps.googleapis.com');
  });

  it('a coordenada que entra no script é sempre número — valor estranho do servidor vira 0', async () => {
    chave.mockReturnValue(null);
    await render(
      <Mapa centro={{ lat: 'alert(1)' as unknown as number, lon: -46.73 }} marcadores={[]} />,
    );

    expect(html()).not.toContain('alert(1)');
    expect(html()).toContain('setView([0, -46.73]');
  });

  it('repassa toque em marcador, região (com raio inteiro) e toque no mapa', async () => {
    chave.mockReturnValue(null);
    const aoTocarMarcador = jest.fn();
    const aoMudarRegiao = jest.fn();
    const aoTocarMapa = jest.fn();
    await render(
      <Mapa
        centro={{ lat: -23.54, lon: -46.73 }}
        marcadores={MARCADORES}
        aoTocarMarcador={aoTocarMarcador}
        aoMudarRegiao={aoMudarRegiao}
        aoTocarMapa={aoTocarMapa}
      />,
    );

    await avisar({ tipo: 'marcador', id: 'missao:1' });
    await avisar({ tipo: 'regiao', lat: -23.5, lon: -46.7, raioM: 812.6 });
    await avisar({ tipo: 'toque', lat: -23.51, lon: -46.71 });

    expect(aoTocarMarcador).toHaveBeenCalledWith('missao:1');
    expect(aoMudarRegiao).toHaveBeenCalledWith({ lat: -23.5, lon: -46.7, raioM: 813 });
    expect(aoTocarMapa).toHaveBeenCalledWith(-23.51, -46.71);
  });

  it('mensagem malformada é ignorada em vez de derrubar a tela', async () => {
    chave.mockReturnValue(null);
    await render(<Mapa centro={{ lat: -23.54, lon: -46.73 }} marcadores={MARCADORES} />);

    await act(async () => {
      webview().props.onMessage({ nativeEvent: { data: '{nao é json' } });
    });

    expect(webview()).toBeTruthy();
  });
});
