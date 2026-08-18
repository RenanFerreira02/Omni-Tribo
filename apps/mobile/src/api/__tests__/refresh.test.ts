import { HttpResponse, http } from 'msw';

import { _limparRotacaoEmVoo, cliente } from '../cliente';
import { problema } from '@/testes/fixtures';
import { servidor } from '@/testes/servidor';
import { lerRefreshPersistido, useSessao } from '@/stores/sessao';

const BASE = 'http://api.teste/api/v1';

function um401() {
  return HttpResponse.json(problema('nao-autenticado', 401, 'Autenticação necessária'), {
    status: 401,
  });
}

beforeEach(async () => {
  _limparRotacaoEmVoo();
  await useSessao.getState().definirSessao({
    accessToken: 'access-velho',
    refreshToken: 'refresh-1',
    tipoToken: 'Bearer',
    expiresIn: 900,
  });
});

afterEach(async () => {
  await useSessao.getState().encerrar();
});

describe('rotação de refresh no 401', () => {
  it('rotaciona uma vez e reenvia a requisição com o token novo', async () => {
    let tentativas = 0;
    const autorizacoes: (string | null)[] = [];

    servidor.use(
      http.get(`${BASE}/carteira`, ({ request }) => {
        tentativas += 1;
        autorizacoes.push(request.headers.get('Authorization'));
        // Primeira chamada: token expirado. Segunda: já com o token rotacionado.
        return tentativas === 1 ? um401() : HttpResponse.json({ saldoTokens: 41 });
      }),
    );

    const resposta = await cliente.get('/carteira');

    expect(resposta.status).toBe(200);
    expect(tentativas).toBe(2);
    expect(autorizacoes[0]).toBe('Bearer access-velho');
    expect(autorizacoes[1]).toBe('Bearer access-2');
    expect(useSessao.getState().accessToken).toBe('access-2');
  });

  it('N requisições concorrentes disparam UM único POST /auth/refresh', async () => {
    // Este é o teste que o desenho existe para satisfazer. Com uma rotação por requisição, o
    // backend receberia N apresentações do MESMO refresh token; ele rotaciona a cada chamada e
    // trata reapresentação de token já rotacionado como indício de roubo, REVOGANDO A FAMÍLIA
    // INTEIRA. O app se deslogaria sozinho justamente por tentar se manter logado.
    let refreshes = 0;
    const jaFalhou = new Set<string>();

    servidor.use(
      http.post(`${BASE}/auth/refresh`, () => {
        refreshes += 1;
        return HttpResponse.json({
          accessToken: 'access-2',
          refreshToken: 'refresh-2',
          tipoToken: 'Bearer',
          expiresIn: 900,
        });
      }),
      http.get(`${BASE}/missoes`, ({ request }) => {
        const marcador = new URL(request.url).searchParams.get('marcador') ?? '';
        if (!jaFalhou.has(marcador)) {
          jaFalhou.add(marcador);
          return um401();
        }
        return HttpResponse.json({ conteudo: [], marcador });
      }),
    );

    const respostas = await Promise.all([
      cliente.get('/missoes', { params: { marcador: 'a' } }),
      cliente.get('/missoes', { params: { marcador: 'b' } }),
      cliente.get('/missoes', { params: { marcador: 'c' } }),
    ]);

    expect(refreshes).toBe(1);
    // E as três foram reenfileiradas e concluíram — nenhuma foi descartada pelo caminho.
    expect(respostas.map((r) => r.status)).toEqual([200, 200, 200]);
    expect(respostas.map((r) => r.data.marcador).sort()).toEqual(['a', 'b', 'c']);
  });

  it('não entra em laço quando o reenvio também toma 401', async () => {
    let chamadas = 0;
    servidor.use(
      http.get(`${BASE}/carteira`, () => {
        chamadas += 1;
        return um401();
      }),
    );

    await expect(cliente.get('/carteira')).rejects.toMatchObject({ tipo: 'naoAutenticado' });
    // Original + UM reenvio. Sem a marca `_jaTentouRefresh` isto seria infinito.
    expect(chamadas).toBe(2);
  });

  it('refresh recusado encerra a sessão e apaga o refresh persistido', async () => {
    servidor.use(
      http.get(`${BASE}/carteira`, () => um401()),
      http.post(`${BASE}/auth/refresh`, () =>
        HttpResponse.json(problema('nao-autenticado', 401, 'Sessão inválida'), { status: 401 }),
      ),
    );

    await expect(cliente.get('/carteira')).rejects.toMatchObject({ tipo: 'naoAutenticado' });

    expect(useSessao.getState().accessToken).toBeNull();
    expect(useSessao.getState().refreshToken).toBeNull();
    // O COFRE, não só a memória. O título sempre prometeu isto e a asserção não existia — como
    // `apagarSeguro` engole falha por desenho, um refresh órfão sobrevivendo no keystore deixava o
    // teste verde. É metade do que ele diz garantir.
    await expect(lerRefreshPersistido()).resolves.toBeNull();
  });

  /**
   * 429 no refresh NÃO pode custar a sessão — este é o teste que falharia antes da correção.
   *
   * O backend passou a aplicar rate limit ao `/auth/refresh` (a isenção anterior se apoiava numa
   * premissa falsa). Como o app tratava qualquer falha da rotação como sessão morta, e `encerrar()`
   * apaga o keystore, um limite de um minuto destruía um refresh de 30 dias — irreversível.
   */
  it('429 na rotação preserva a sessão e o refresh no cofre', async () => {
    servidor.use(
      http.get(`${BASE}/carteira`, () => um401()),
      http.post(`${BASE}/auth/refresh`, () =>
        HttpResponse.json(problema('limite-requisicoes', 429, 'Muitas tentativas.'), {
          status: 429,
        }),
      ),
    );

    await expect(cliente.get('/carteira')).rejects.toMatchObject({ tipo: 'naoAutenticado' });

    // A requisição falha — não há token novo para reenviar. Mas a SESSÃO sobrevive:
    expect(useSessao.getState().refreshToken).toBe('refresh-1');
    await expect(lerRefreshPersistido()).resolves.toBe('refresh-1');
  });

  /** Mesma regra para queda de rede: o token continua válido, só não deu para usar agora. */
  it('falha de rede na rotação preserva a sessão e o refresh no cofre', async () => {
    servidor.use(
      http.get(`${BASE}/carteira`, () => um401()),
      http.post(`${BASE}/auth/refresh`, () => HttpResponse.error()),
    );

    await expect(cliente.get('/carteira')).rejects.toMatchObject({ tipo: 'naoAutenticado' });

    expect(useSessao.getState().refreshToken).toBe('refresh-1');
    await expect(lerRefreshPersistido()).resolves.toBe('refresh-1');
  });

  it('401 do próprio login NÃO dispara rotação', async () => {
    let refreshes = 0;
    servidor.use(
      http.post(`${BASE}/auth/refresh`, () => {
        refreshes += 1;
        return HttpResponse.json({
          accessToken: 'x',
          refreshToken: 'y',
          tipoToken: 'Bearer',
          expiresIn: 900,
        });
      }),
      http.post(`${BASE}/auth/login`, () =>
        HttpResponse.json(problema('nao-autenticado', 401, 'Credenciais inválidas'), {
          status: 401,
        }),
      ),
    );

    await expect(
      cliente.post('/auth/login', { email: 'a@b.dev', senha: 'x' }),
    ).rejects.toMatchObject({ tipo: 'naoAutenticado' });

    // Credencial errada não é sessão expirada. Rotacionar aqui gastaria o refresh do usuário
    // anterior por causa de um erro de digitação de outra pessoa.
    expect(refreshes).toBe(0);
  });

  it('403 não dispara rotação: o token é válido, falta permissão', async () => {
    let refreshes = 0;
    servidor.use(
      http.post(`${BASE}/auth/refresh`, () => {
        refreshes += 1;
        return HttpResponse.json({
          accessToken: 'x',
          refreshToken: 'y',
          tipoToken: 'Bearer',
          expiresIn: 900,
        });
      }),
      http.get(`${BASE}/carteira`, () =>
        HttpResponse.json(problema('acesso-negado', 403, 'Acesso negado'), { status: 403 }),
      ),
    );

    await expect(cliente.get('/carteira')).rejects.toMatchObject({ tipo: 'acessoNegado' });
    expect(refreshes).toBe(0);
  });
});

describe('cabeçalhos de toda requisição', () => {
  it('injeta Bearer e X-Correlation-Id', async () => {
    let autorizacao: string | null = null;
    let correlacao: string | null = null;

    servidor.use(
      http.get(`${BASE}/carteira`, ({ request }) => {
        autorizacao = request.headers.get('Authorization');
        correlacao = request.headers.get('X-Correlation-Id');
        return HttpResponse.json({ ok: true });
      }),
    );

    await cliente.get('/carteira');

    expect(autorizacao).toBe('Bearer access-velho');
    expect(correlacao).toBeTruthy();
  });
});
