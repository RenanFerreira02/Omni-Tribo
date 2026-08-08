import { HttpResponse, http } from 'msw';

import { CARTEIRA, LANCAMENTO, TOKENS, USUARIO, missao, pagina, proxima } from './fixtures';

const BASE = 'http://api.teste/api/v1';

/** Caminho feliz padrão. Cada teste sobrescreve o que precisa com `servidor.use(...)`. */
export const manipuladores = [
  http.post(`${BASE}/auth/login`, () => HttpResponse.json(TOKENS)),
  http.post(`${BASE}/auth/registrar`, () => HttpResponse.json(TOKENS, { status: 201 })),
  http.post(`${BASE}/auth/refresh`, () =>
    HttpResponse.json({ ...TOKENS, accessToken: 'access-2', refreshToken: 'refresh-2' }),
  ),
  http.post(`${BASE}/auth/logout`, () => new HttpResponse(null, { status: 204 })),
  http.get(`${BASE}/auth/me`, () => HttpResponse.json(USUARIO)),

  http.get(`${BASE}/missoes`, () => HttpResponse.json(pagina([missao()]))),
  http.get(`${BASE}/missoes/proximas`, () => HttpResponse.json([proxima(412.7)])),
  http.get(`${BASE}/missoes/:id`, () => HttpResponse.json(missao())),

  http.get(`${BASE}/carteira`, () => HttpResponse.json(CARTEIRA)),
  http.get(`${BASE}/carteira/lancamentos`, () => HttpResponse.json(pagina([LANCAMENTO]))),
];
