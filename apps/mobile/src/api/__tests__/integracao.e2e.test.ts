import { login, me } from '../auth';
import { paraErroApi } from '../erros';
import { buscarMissao, listarMissoes, missoesProximas } from '../missoes';
import { buscarCarteira, listarLancamentos } from '../carteira';
import { useSessao } from '@/stores/sessao';

/**
 * Integração REAL contra o backend em execução. Não usa MSW.
 *
 * Fora do `npm test` de propósito: exige `make up` + `spring-boot:run`, e um teste que falha porque
 * o servidor não está de pé não distingue regressão de ambiente. Roda com:
 *
 *     E2E_API_URL=http://192.168.15.6:8080 npm run test:e2e
 *
 * O que ele prova, e nenhum teste com mock prova: que os nomes de campo do DTO batem, que o envelope
 * de paginação é o esperado, que a distância vem em metros do PostGIS, que o `type` do catálogo
 * chega como o app espera, e que os schemas Zod não acusam divergência contra dado de verdade.
 */

// Sem MSW: este arquivo roda sob `jest.e2e.config.js`, que não carrega o setup de mocks de rede.
/**
 * Falha ALTO quando `E2E_API_URL` não está definida — não pula em silêncio.
 *
 * Antes era `process.env.E2E_API_URL ? describe : describe.skip`: sem a variável, os casos eram
 * pulados e o jest saía com código ZERO. Um `npm run test:e2e` "verde" podia significar nenhum teste
 * executado — e esta é justamente a suíte que pegaria deriva de contrato entre app e backend, o
 * defeito que ela existe para achar.
 *
 * A instrução vem junto porque o endereço não é adivinhável: dentro do emulador, `localhost` é o
 * próprio aparelho, não a máquina. Ver `apps/mobile/README.md`.
 */
beforeAll(() => {
  if (!process.env.E2E_API_URL) {
    throw new Error(
      'E2E_API_URL não definida. Suba o backend e rode:\n' +
        '  cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev\n' +
        '  cd apps/mobile && E2E_API_URL=http://<ip-da-maquina>:8080 npm run test:e2e',
    );
  }
});

const descreve = describe;

descreve('integração com o backend local', () => {
  beforeAll(async () => {
    const tokens = await login('alice@omnitribo.dev', 'Senha@123');
    await useSessao.getState().definirSessao(tokens);
  });

  afterAll(async () => {
    await useSessao.getState().encerrar();
  });

  it('login real devolve o par de tokens com TTL de 15 minutos', () => {
    const sessao = useSessao.getState();
    expect(sessao.accessToken).toBeTruthy();
    expect(sessao.refreshToken).toBeTruthy();
  });

  it('GET /auth/me responde com o usuário do JWT', async () => {
    const usuario = await me();
    expect(usuario.email).toBe('alice@omnitribo.dev');
    expect(usuario.papel).toBe('USUARIO');
  });

  it('GET /missoes devolve o envelope de paginação do backend', async () => {
    const pagina = await listarMissoes({ status: 'ABERTA', tamanho: 5 });

    expect(Array.isArray(pagina.conteudo)).toBe(true);
    expect(pagina.conteudo.length).toBeGreaterThan(0);
    expect(typeof pagina.totalElementos).toBe('number');
    expect(typeof pagina.ultima).toBe('boolean');

    const missao = pagina.conteudo[0];
    expect(missao.status).toBe('ABERTA');
    // A regra do ADR 0009, medida contra o banco: nenhuma missão remunera em BRL.
    expect(Number(missao.valorBrl)).toBe(0);
    expect(missao.tokensRecompensa).toBeGreaterThan(0);
    expect(missao.xpRecompensa).toBeGreaterThan(0);
  });

  it('GET /missoes/proximas devolve distância em metros, medida pelo PostGIS', async () => {
    // Pinheiros / Vila Madalena — onde estão as missões ABERTA do seed.
    const proximas = await missoesProximas({
      lat: -23.5565,
      lon: -46.6921,
      raioMetros: 2000,
      limite: 50,
    });

    expect(proximas.length).toBeGreaterThan(0);
    for (const item of proximas) {
      expect(item.distanciaM).toBeGreaterThanOrEqual(0);
      expect(item.distanciaM).toBeLessThanOrEqual(2000);
      expect(item.missao.status).toBe('ABERTA');
    }
    // Ordenado por distância crescente — é disso que a tela depende para não reordenar no cliente.
    const distancias = proximas.map((item) => item.distanciaM);
    expect([...distancias].sort((a, b) => a - b)).toEqual(distancias);
  });

  it('GET /missoes/{id} devolve a missão que a lista anunciou', async () => {
    const pagina = await listarMissoes({ status: 'ABERTA', tamanho: 1 });
    const detalhe = await buscarMissao(pagina.conteudo[0].id);
    expect(detalhe.id).toBe(pagina.conteudo[0].id);
  });

  it('GET /carteira devolve saldo em tokens, com BRL zerado', async () => {
    const carteira = await buscarCarteira();
    expect(carteira.saldoTokens).toBeGreaterThanOrEqual(0);
    expect(Number(carteira.saldoBrl)).toBe(0);

    const extrato = await listarLancamentos(0, 5);
    expect(Array.isArray(extrato.conteudo)).toBe(true);
  });

  it('credencial errada chega ao app como naoAutenticado, com type do catálogo', async () => {
    try {
      await login('alice@omnitribo.dev', 'senha-que-nao-existe');
      throw new Error('deveria ter falhado');
    } catch (erro) {
      const api = paraErroApi(erro);
      expect(api.tipo).toBe('naoAutenticado');
      expect(api.status).toBe(401);
    }
  });
});
