import { HttpResponse, http } from 'msw';
import {
  ALERTA_PAGINA,
  CARTEIRA,
  CLIMA,
  CONSENTIMENTOS,
  ENDERECO,
  LANCAMENTO,
  PERFIL,
  PONTO_CUSTODIA,
  PREVIA,
  TOKENS,
  TRIBO,
  USUARIO,
  missao,
  pagina,
  proxima,
} from './fixtures';

const BASE = 'http://api.teste/api/v1';

/**
 * Caminho feliz padrão. Cada teste sobrescreve o que precisa com `servidor.use(...)`.
 *
 * `onUnhandledRequest: 'error'` (jest.setup.ts) faz qualquer rota SEM handler falhar alto. É
 * intencional — um teste que bate numa rota não declarada está testando a rede de verdade —, e a
 * consequência é que toda tela nova exige acrescentar o handler aqui antes.
 */
export const manipuladores = [
  http.post(`${BASE}/auth/login`, () => HttpResponse.json(TOKENS)),
  http.post(`${BASE}/auth/registrar`, () => HttpResponse.json(TOKENS, { status: 201 })),
  http.post(`${BASE}/auth/refresh`, () =>
    HttpResponse.json({ ...TOKENS, accessToken: 'access-2', refreshToken: 'refresh-2' }),
  ),
  http.post(`${BASE}/auth/logout`, () => new HttpResponse(null, { status: 204 })),
  http.get(`${BASE}/auth/me`, () => HttpResponse.json(USUARIO)),

  // ─── Missões ────────────────────────────────────────────────────────────────────────────────
  http.get(`${BASE}/missoes`, () => HttpResponse.json(pagina([missao()]))),
  http.get(`${BASE}/missoes/proximas`, () => HttpResponse.json([proxima(412.7)])),
  http.post(`${BASE}/missoes/previa-recompensa`, () => HttpResponse.json(PREVIA)),
  http.post(`${BASE}/missoes`, () => HttpResponse.json(missao({ status: 'RASCUNHO' }))),
  http.get(`${BASE}/missoes/:id`, () => HttpResponse.json(missao())),
  http.post(`${BASE}/missoes/:id/checkin`, () =>
    HttpResponse.json(missao({ status: 'AGUARDANDO_CONFIRMACAO' })),
  ),
  // Uma rota por ação: o `:acao` do path é o que muda o estado devolvido, e devolver sempre o
  // mesmo status faria a tela parecer funcionar em transições que não testamos.
  http.post(`${BASE}/missoes/:id/publicar`, () => HttpResponse.json(missao({ status: 'ABERTA' }))),
  http.post(`${BASE}/missoes/:id/aceitar`, () =>
    HttpResponse.json(missao({ status: 'ACEITA', executorId: USUARIO.id })),
  ),
  http.post(`${BASE}/missoes/:id/iniciar`, () =>
    HttpResponse.json(missao({ status: 'EM_ANDAMENTO', executorId: USUARIO.id })),
  ),
  http.post(`${BASE}/missoes/:id/desistir`, () =>
    HttpResponse.json(missao({ status: 'ABERTA', executorId: null })),
  ),
  http.post(`${BASE}/missoes/:id/cancelar`, () =>
    HttpResponse.json(missao({ status: 'CANCELADA' })),
  ),
  http.post(`${BASE}/missoes/:id/contestar`, () =>
    HttpResponse.json(missao({ status: 'EM_DISPUTA' })),
  ),
  http.post(`${BASE}/missoes/:id/confirmar`, () =>
    HttpResponse.json(missao({ status: 'CONCLUIDA' })),
  ),

  // ─── Carteira ───────────────────────────────────────────────────────────────────────────────
  http.get(`${BASE}/carteira`, () => HttpResponse.json(CARTEIRA)),
  http.get(`${BASE}/carteira/lancamentos`, () => HttpResponse.json(pagina([LANCAMENTO]))),
  http.post(`${BASE}/carteira/transferencias`, () =>
    HttpResponse.json(
      {
        lancamentoSaidaId: 'eeeeeeee-0000-0000-0000-000000000002',
        lancamentoEntradaId: 'eeeeeeee-0000-0000-0000-000000000003',
        saldoTokensRemetente: 31,
        replay: false,
      },
      { status: 201 },
    ),
  ),

  // ─── Perfil e LGPD ──────────────────────────────────────────────────────────────────────────
  http.get(`${BASE}/usuarios/me`, () => HttpResponse.json(PERFIL)),
  http.get(`${BASE}/usuarios/me/consentimentos`, () => HttpResponse.json(CONSENTIMENTOS)),
  http.put(`${BASE}/usuarios/me/consentimentos/:tipo`, async ({ params, request }) => {
    const corpo = (await request.json()) as { concedido: boolean; versaoTexto: string };
    return HttpResponse.json({
      tipo: params.tipo,
      concedido: corpo.concedido,
      versaoTexto: corpo.versaoTexto,
      registradoEm: '2026-08-09T00:00:00Z',
    });
  }),
  http.get(`${BASE}/usuarios/me/dados`, () =>
    HttpResponse.json({ geradoEm: '2026-08-09T00:00:00Z', identidade: [], missoes: [] }),
  ),
  http.delete(`${BASE}/usuarios/me`, () => new HttpResponse(null, { status: 204 })),

  // ─── Notificações ───────────────────────────────────────────────────────────────────────────
  http.get(`${BASE}/alertas/nao-lidos/contagem`, () => HttpResponse.json({ naoLidos: 2 })),
  http.get(`${BASE}/alertas`, () => HttpResponse.json(ALERTA_PAGINA)),
  http.patch(`${BASE}/alertas/:id/lido`, () =>
    HttpResponse.json({ ...ALERTA_PAGINA.conteudo[0], lido: true }),
  ),

  // ─── Lugares e integrações ──────────────────────────────────────────────────────────────────
  http.get(`${BASE}/tribos`, () =>
    HttpResponse.json([{ ...TRIBO, centroLat: null, centroLon: null }]),
  ),
  http.get(`${BASE}/tribos/:id`, () => HttpResponse.json(TRIBO)),
  http.get(`${BASE}/pontos-custodia`, () => HttpResponse.json([PONTO_CUSTODIA])),
  http.get(`${BASE}/pontos-custodia/:id`, () =>
    HttpResponse.json({ ...PONTO_CUSTODIA, distanciaM: null }),
  ),
  http.get(`${BASE}/clima`, () => HttpResponse.json(CLIMA)),
  http.get(`${BASE}/enderecos/:cep`, () => HttpResponse.json(ENDERECO)),
];
