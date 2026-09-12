/*
 * Teste de carga do Omni-Tribo — três cenários, cinco minutos cada, contra o compose local.
 *
 * ESCALA HONESTA, DECLARADA AQUI E NA EVIDÊNCIA: uma máquina, um Postgres, dado de seed, rede
 * loopback. Não é bancada distribuída e não pretende ser — ver docs/evidencias/f21-carga.md, seção
 * "o que isto NÃO prova".
 *
 * NADA É AJUSTADO PARA MELHORAR O NÚMERO. A API sobe com o perfil `dev` exatamente como está:
 * pool Hikari no default (10), rate limit de produção (300 GET/min e 100 POST/min por usuário, 120
 * req/min por transportadora). Se o limitador barrar antes do banco, o achado é esse — e é um
 * achado, não um obstáculo a contornar.
 *
 * Uso:  bash tools/carga/executar.sh
 */
import http from 'k6/http';
import crypto from 'k6/crypto';
import exec from 'k6/execution';
import { check } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';

const API = __ENV.API || 'http://localhost:8080';
const SENHA = __ENV.SENHA_SEED || 'Senha@123';
const TRANSPORTADORA = __ENV.TRANSPORTADORA || 'transportadora-dev';
const SEGREDO = __ENV.SEGREDO || 'segredo-de-desenvolvimento-local';

/* Ponto de custódia ÚNICO de propósito no cenário 3: a conversão trava a linha do ponto com
 * SELECT ... FOR UPDATE, então mandar todo mundo para o mesmo ponto é o que mede a serialização.
 * Distribuir entre pontos mediria throughput e esconderia exatamente a contenção que interessa. */
const PONTO_CUSTODIA = __ENV.PONTO_CUSTODIA || 'cccccccc-0000-0000-0000-000000000901';

/* SELEÇÃO DE CENÁRIO — e a razão dela é uma medição que saiu errada, não elegância.
 *
 * O cenário `leituras` nasceu como um quarto bloco em `startTime: '16m30s'`, depois dos três
 * originais. Resultado: 1.180 respostas 401 e nenhuma 200. O access token sai do `setup()`, que roda
 * uma vez em t=0, e vale 900 s — aos 16m30s ele já tinha expirado havia mais de quinze minutos.
 *
 * Os 429 que vieram junto são o SEGUNDO efeito do mesmo erro, e ele é instrutivo: com o JWT
 * inválido, `RateLimitFilter.resolverChave` não acha o `sub` e cai para `"ip:" + endereço` — os
 * sessenta VUs, que deveriam ter um balde por usuário, passam a dividir UM balde de 300/min.
 *
 * A saída é rodar as leituras num processo k6 PRÓPRIO, com `setup()` fresco. Os três cenários
 * originais continuam no mesmo processo, nos mesmos instantes, byte a byte como em
 * docs/evidencias/f21-carga.md — misturá-los para acomodar o quarto teria custado a comparabilidade
 * que é a única razão de eles existirem. `executar.sh` faz as duas invocações. */
const SO_LEITURAS = __ENV.CENARIO === 'leituras';

/* Zona leste, seed V903 (Cidade Líder) — é onde há missão ABERTA com origem para o radar achar. */
const LAT_BASE = -23.55737;
const LON_BASE = -46.46987;

/* Os nove usuários do seed com senha conhecida. Espalhar entre eles não é truque: o balde do rate
 * limit é POR USUÁRIO, então um único login limitaria o teste a 5 req/s e mediria o bucket4j. */
const USUARIOS = [
  'admin@omnitribo.dev', 'alice@omnitribo.dev', 'bob@omnitribo.dev',
  'carol@omnitribo.dev', 'diana@omnitribo.dev', 'erik@omnitribo.dev',
  'renan@omnitribo.dev', 'marlene@omnitribo.dev', 'jonas@omnitribo.dev',
];

/* Cenário 2 — os três da MESMA tribo (Cidade Líder). Toda transferência tem `renan` numa das
 * pontas, então todas disputam a MESMA linha de carteira: é isso que exercita o lock ordenado. */
const CL = {
  renan: 'bbbbbbbb-0000-0000-0000-000000000901',
  marlene: 'bbbbbbbb-0000-0000-0000-000000000902',
  jonas: 'bbbbbbbb-0000-0000-0000-000000000903',
};
const PARES = [
  ['marlene@omnitribo.dev', CL.renan],
  ['jonas@omnitribo.dev', CL.renan],
  ['renan@omnitribo.dev', CL.marlene],
  ['renan@omnitribo.dev', CL.jonas],
];

// ── Métricas próprias ────────────────────────────────────────────────────────────────────────
const radarQuente = new Trend('radar_cache_quente_ms', true);
const radarFrio = new Trend('radar_cache_frio_ms', true);
const transferencia = new Trend('transferencia_ms', true);
const webhook = new Trend('webhook_ms', true);

/* Cenário 4 — leituras. Quatro Trends e não uma, porque a pergunta do cenário é COMPARATIVA: a
 * listagem ordenada por `criadaEm` tem `idx_missao_status_criada` (V11) atrás dela; a ordenada por
 * `tokensRecompensa` não tem índice nenhum. Uma métrica só somaria as duas e apagaria justamente a
 * diferença que o cenário existe para medir. */
const listaOrdemIndexada = new Trend('lista_ordem_indexada_ms', true);
const listaOrdemSemIndice = new Trend('lista_ordem_sem_indice_ms', true);
const detalhe = new Trend('detalhe_ms', true);
const extrato = new Trend('extrato_ms', true);

const erros = new Rate('erros_inesperados');
const c429 = new Counter('respostas_429');
const c422 = new Counter('respostas_422');
const convertidas = new Counter('webhook_convertida');
const semPatrocinio = new Counter('webhook_sem_patrocinio');
const recusadas = new Counter('webhook_recusada');

const CENARIO_LEITURAS = {
  executor: 'ramping-arrival-rate',
  startRate: 5, timeUnit: '1s',
  preAllocatedVUs: 60, maxVUs: 250,
  stages: [
    { target: 10, duration: '1m' },
    { target: 20, duration: '1m' },
    { target: 40, duration: '1m' },
    { target: 60, duration: '1m' },
    { target: 80, duration: '1m' },
  ],
  exec: 'cenarioLeituras',
  startTime: '0s',
};

export const options = {
  discardResponseBodies: false,
  scenarios: SO_LEITURAS ? { leituras: CENARIO_LEITURAS } : {
    // Rampa e não taxa constante: o pedido é "o ponto onde degrada", e isso só aparece variando a
    // pressão. Cada patamar dura 1 min, tempo suficiente para o balde do rate limit se estabilizar.
    radar: {
      executor: 'ramping-arrival-rate',
      startRate: 5, timeUnit: '1s',
      preAllocatedVUs: 60, maxVUs: 250,
      stages: [
        { target: 10, duration: '1m' },
        { target: 20, duration: '1m' },
        { target: 40, duration: '1m' },
        { target: 60, duration: '1m' },
        { target: 80, duration: '1m' },
      ],
      exec: 'cenarioRadar',
      startTime: '0s',
    },
    transferencias: {
      executor: 'ramping-arrival-rate',
      startRate: 1, timeUnit: '1s',
      preAllocatedVUs: 40, maxVUs: 150,
      stages: [
        { target: 2, duration: '1m' },
        { target: 5, duration: '1m' },
        { target: 10, duration: '1m' },
        { target: 15, duration: '1m' },
        { target: 20, duration: '1m' },
      ],
      exec: 'cenarioTransferencia',
      startTime: '5m30s',
    },
    webhookEntregaFalida: {
      executor: 'ramping-arrival-rate',
      startRate: 1, timeUnit: '1s',
      preAllocatedVUs: 40, maxVUs: 150,
      stages: [
        { target: 2, duration: '1m' },
        { target: 4, duration: '1m' },
        { target: 8, duration: '1m' },
        { target: 12, duration: '1m' },
        { target: 16, duration: '1m' },
      ],
      exec: 'cenarioWebhook',
      startTime: '11m0s',
    },
  },
};

/* Login UMA vez por usuário. O teto de login é 5/min por (IP, e-mail): relogar por iteração
 * garantiria 429 nos primeiros segundos e o teste mediria o bloqueio progressivo, não a API. */
export function setup() {
  const tokens = {};
  for (const email of USUARIOS) {
    const r = http.post(`${API}/api/v1/auth/login`, JSON.stringify({ email, senha: SENHA }), {
      headers: { 'Content-Type': 'application/json' },
    });
    if (r.status !== 200) {
      throw new Error(`login falhou para ${email}: ${r.status} ${r.body}`);
    }
    tokens[email] = r.json('accessToken');
  }

  /* Ids de missão para o cenário 4, colhidos AQUI e não fixados no script: os UUIDs do seed mudam
   * quando uma migration da faixa 900+ muda, e um id literal transformaria uma alteração de seed
   * num cenário silenciosamente medindo 404. Vazio é tolerado — `cenarioLeituras` cai para a
   * listagem, que não depende de id. */
  const primeiro = USUARIOS[0];
  const lista = http.get(
    `${API}/api/v1/missoes?tamanho=100`,
    { headers: { Authorization: `Bearer ${tokens[primeiro]}` } },
  );
  const missoes = lista.status === 200 ? lista.json('conteudo').map((m) => m.id) : [];
  console.log(`setup: ${Object.keys(tokens).length} tokens, ${missoes.length} missões para o detalhe`);

  return { tokens, missoes };
}

function auth(token) {
  return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } };
}

// ── Cenário 1 — radar geoespacial ────────────────────────────────────────────────────────────
export function cenarioRadar(dados) {
  const i = exec.scenario.iterationInTest;
  const email = USUARIOS[i % USUARIOS.length];
  const quente = i % 2 === 0;

  /* QUENTE: coordenada fixa → sempre a mesma célula de geohash (precisão 7) → deve sair do cache
   * Caffeine depois da primeira. FRIO: grade de 60×60 células deslocadas de 0,0025° (~275 m, mais
   * que a diagonal de 216 m da célula), então cada ponto cai numa célula diferente e força o
   * ST_DWithin a ir ao índice GiST. A diferença entre as duas curvas é a medição do cache. */
  let lat = LAT_BASE;
  let lon = LON_BASE;
  if (!quente) {
    lat = LAT_BASE + (((i % 60) - 30) * 0.0025);
    lon = LON_BASE + (((Math.floor(i / 60) % 60) - 30) * 0.0025);
  }

  const url = `${API}/api/v1/missoes/proximas?lat=${lat.toFixed(5)}&lon=${lon.toFixed(5)}` +
              `&raioMetros=5000&limite=50`;
  const r = http.get(url, auth(dados.tokens[email]));

  if (r.status === 429) {
    c429.add(1);
  } else if (r.status === 200) {
    (quente ? radarQuente : radarFrio).add(r.timings.duration);
  }
  // 429 é recusa DESENHADA, não falha: contabilizada à parte para não poluir a taxa de erro.
  erros.add(r.status !== 200 && r.status !== 429);
  check(r, { 'radar 200 ou 429': (x) => x.status === 200 || x.status === 429 });
}

// ── Cenário 2 — transferências disputando a MESMA carteira ───────────────────────────────────
export function cenarioTransferencia(dados) {
  const i = exec.scenario.iterationInTest;
  const [email, destinatarioId] = PARES[i % PARES.length];

  const corpo = JSON.stringify({ destinatarioId, tokens: 1, mensagem: 'carga' });
  const opcoes = auth(dados.tokens[email]);
  /* Chave ÚNICA por iteração de propósito: chave repetida vira replay, que devolve 201 sem pedir o
   * segundo lock — mediria o atalho da idempotência, não a contenção. */
  opcoes.headers['Idempotency-Key'] = `carga-${exec.scenario.name}-${i}-${Date.now()}`;

  const r = http.post(`${API}/api/v1/carteira/transferencias`, corpo, opcoes);

  if (r.status === 429) {
    c429.add(1);
  } else if (r.status === 422) {
    c422.add(1); // saldo ou janela de 24 h — regra de negócio, não falha de carga
  } else if (r.status === 201) {
    transferencia.add(r.timings.duration);
  }
  erros.add(![201, 422, 429].includes(r.status));
  check(r, { 'transferência 201/422/429': (x) => [201, 422, 429].includes(x.status) });
}

// ── Cenário 3 — rajada no webhook de entrega falida ──────────────────────────────────────────
export function cenarioWebhook() {
  const i = exec.scenario.iterationInTest;
  const rastreio = `CARGA${exec.vu.idInTest}-${i}-${Date.now()}`;

  const payload = {
    codigoRastreio: rastreio,
    motivo: 'Destinatário ausente após 3 tentativas de entrega',
    pontoCustodiaId: PONTO_CUSTODIA,
    descricaoDoItem: '2 caixas de porcelanato 60x60',
    pesoKg: 24.5, volumeL: 58.0, valorOfertadoBrl: 35.0,
    destinoLat: -23.5569, destinoLon: -46.4698,
    cep: '08280460', logradouro: 'Rua Antônio Maria Bessa',
    bairro: 'Cidade Líder', cidade: 'São Paulo', uf: 'SP',
    janelaHoraInicio: 14, tipoEndereco: 'RESIDENCIAL', tentativasAnteriores: 3,
  };

  /* O HMAC cobre timestamp + "." + CORPO BRUTO. A mesma string tem de ser assinada e enviada — se
   * o k6 reserializasse o objeto, um espaço de diferença invalidaria a assinatura. */
  const corpo = JSON.stringify(payload);
  const ts = Math.floor(Date.now() / 1000).toString();
  const assinatura = crypto.hmac('sha256', SEGREDO, `${ts}.${corpo}`, 'hex');

  const r = http.post(`${API}/api/v1/webhooks/transportadora`, corpo, {
    headers: {
      'Content-Type': 'application/json',
      'X-Transportadora': TRANSPORTADORA,
      'X-Timestamp': ts,
      'X-Assinatura': assinatura,
    },
  });

  if (r.status === 429) {
    c429.add(1);
  } else if (r.status === 200) {
    webhook.add(r.timings.duration);
    const d = r.json('desfecho');
    if (d === 'CONVERTIDA') convertidas.add(1);
    else if (d === 'SEM_PATROCINIO') semPatrocinio.add(1);
    else if (d === 'RECUSADA') recusadas.add(1);
  }
  erros.add(r.status !== 200 && r.status !== 429);
  check(r, { 'webhook 200 ou 429': (x) => x.status === 200 || x.status === 429 });
}

// ── Cenário 4 — leituras: listagem com filtro, detalhe e extrato ──────────────────────────────
/*
 * Os três caminhos de leitura que os cenários 1-3 nunca exercitaram. Rotação de QUATRO e não de
 * três: a listagem entra duas vezes, com as duas ordenações, porque é a comparação entre elas que
 * responde se `ORDER BY tokens_recompensa` — que não tem índice — custa alguma coisa sob carga.
 *
 * `MissaoFiltroRequest.CampoOrdenacao` é uma whitelist justamente para impedir "ordenar por coluna
 * sem índice", nas palavras do javadoc; duas das quatro entradas dela são colunas sem índice. Este
 * cenário mede se isso importa, em vez de supor nos dois sentidos.
 */
export function cenarioLeituras(dados) {
  const i = exec.scenario.iterationInTest;
  const email = USUARIOS[i % USUARIOS.length];
  const opcoes = auth(dados.tokens[email]);

  let r;
  let trend;
  switch (i % 4) {
    case 0:
      // ORDER BY criada_em DESC — servido por idx_missao_status_criada (V11).
      r = http.get(
        `${API}/api/v1/missoes?status=ABERTA&tamanho=20&ordenarPor=CRIADA_EM&direcao=DESC`,
        opcoes,
      );
      trend = listaOrdemIndexada;
      break;
    case 1:
      // ORDER BY tokens_recompensa DESC — nenhum índice cobre esta coluna.
      r = http.get(
        `${API}/api/v1/missoes?status=ABERTA&tamanho=20&ordenarPor=TOKENS_RECOMPENSA&direcao=DESC`,
        opcoes,
      );
      trend = listaOrdemSemIndice;
      break;
    case 2:
      if (dados.missoes.length === 0) {
        return;
      }
      // `i / 4` e não `i`: só uma iteração em quatro cai neste ramo, então indexar por `i`
      // percorreria apenas os índices ≡ 2 (mod 4) — cinco missões das vinte, sempre as mesmas, e o
      // cache do Postgres mediria essas cinco em vez do endpoint.
      r = http.get(
        `${API}/api/v1/missoes/${dados.missoes[Math.floor(i / 4) % dados.missoes.length]}`,
        opcoes,
      );
      trend = detalhe;
      break;
    default:
      r = http.get(`${API}/api/v1/carteira/lancamentos?tamanho=20`, opcoes);
      trend = extrato;
      break;
  }

  if (r.status === 429) {
    c429.add(1);
  } else if (r.status === 200) {
    trend.add(r.timings.duration);
  }
  // 404 no detalhe é possível se o seed mudar entre o setup e a execução; conta como erro de
  // propósito, para não passar despercebido como se o cenário tivesse medido alguma coisa.
  erros.add(r.status !== 200 && r.status !== 429);
  check(r, { 'leitura 200 ou 429': (x) => x.status === 200 || x.status === 429 });
}
