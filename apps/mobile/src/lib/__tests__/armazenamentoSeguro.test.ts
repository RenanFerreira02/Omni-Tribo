import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

import { apagarSeguro, gravarSeguro, lerSeguro } from '@/lib/armazenamentoSeguro';

/**
 * O wrapper decide por `Platform.OS` em runtime, e é justamente isso que torna os dois caminhos
 * testáveis. Um arquivo `.web.ts` seria mais idiomático no Metro e invisível aqui: o preset do
 * jest-expo declara `platforms: [android, ios, native]`, então a variante web nunca seria carregada
 * por teste nenhum — e o caminho que quebrou em produção ficaria sem nenhuma assertion.
 */
const osOriginal = Platform.OS;

function fingirPlataforma(os: typeof Platform.OS): void {
  Object.defineProperty(Platform, 'OS', { value: os, configurable: true });
}

afterEach(() => {
  fingirPlataforma(osOriginal);
  jest.clearAllMocks();
});

describe('nativo', () => {
  beforeEach(() => fingirPlataforma('ios'));

  it('delega o ciclo completo ao keystore', async () => {
    await gravarSeguro('chave.nativa', 'segredo');
    expect(SecureStore.setItemAsync).toHaveBeenCalledWith('chave.nativa', 'segredo');

    await expect(lerSeguro('chave.nativa')).resolves.toBe('segredo');
    expect(SecureStore.getItemAsync).toHaveBeenCalledWith('chave.nativa');

    await apagarSeguro('chave.nativa');
    expect(SecureStore.deleteItemAsync).toHaveBeenCalledWith('chave.nativa');
    await expect(lerSeguro('chave.nativa')).resolves.toBeNull();
  });

  it('apagarSeguro não propaga falha do keystore', async () => {
    // Um logout que estoura porque a limpeza falhou é pior que o vazamento que ele evitaria: a
    // exceção sobe de dentro de um catch e apaga o rastro do erro que originou o logout.
    jest
      .mocked(SecureStore.deleteItemAsync)
      .mockRejectedValueOnce(new Error('keystore indisponível'));

    await expect(apagarSeguro('chave.nativa')).resolves.toBeUndefined();
  });
});

describe('web', () => {
  beforeEach(() => fingirPlataforma('web'));

  it('grava, lê e apaga em memória sem tocar o expo-secure-store', async () => {
    // Esta é a assertion que tranca a regressão do relato: na web o módulo nativo resolve para
    // `export default {}`, e QUALQUER chamada ao expo-secure-store estoura com
    // "... is not a function" — no boot, antes da primeira tela.
    await gravarSeguro('chave.web', 'segredo');
    await expect(lerSeguro('chave.web')).resolves.toBe('segredo');

    await apagarSeguro('chave.web');
    await expect(lerSeguro('chave.web')).resolves.toBeNull();

    expect(SecureStore.setItemAsync).not.toHaveBeenCalled();
    expect(SecureStore.getItemAsync).not.toHaveBeenCalled();
    expect(SecureStore.deleteItemAsync).not.toHaveBeenCalled();
  });

  it('devolve null para chave que nunca foi gravada', async () => {
    await expect(lerSeguro('inexistente')).resolves.toBeNull();
  });

  it('não compartilha cofre com o keystore nativo', async () => {
    // O cofre da web é efêmero de propósito — recarregar a aba desloga. Se ele lesse do mesmo
    // lugar que o nativo, a promessa de "nada persistido no browser" seria falsa.
    await gravarSeguro('chave.isolada', 'valor-web');
    fingirPlataforma('ios');

    await expect(lerSeguro('chave.isolada')).resolves.toBeNull();
  });
});
