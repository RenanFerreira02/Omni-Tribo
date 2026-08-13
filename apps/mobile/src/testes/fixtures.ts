import type {
  AlertaResponse,
  CarteiraResponse,
  ClimaResponse,
  ConsentimentoResponse,
  EnderecoResponse,
  LancamentoResponse,
  LoginResponse,
  MeResponse,
  MissaoProximaResponse,
  MissaoResponse,
  PaginaResponse,
  PerfilResponse,
  PontoCustodiaResponse,
  PreviaRecompensaResponse,
  TriboResponse,
} from '@/api/tipos';

export const TOKENS: LoginResponse = {
  accessToken: 'access-1',
  refreshToken: 'refresh-1',
  tipoToken: 'Bearer',
  expiresIn: 900,
};

export const USUARIO: MeResponse = {
  id: 'bbbbbbbb-0000-0000-0000-000000000002',
  email: 'alice@omnitribo.dev',
  papel: 'USUARIO',
};

export function missao(sobrescrever: Partial<MissaoResponse> = {}): MissaoResponse {
  return {
    id: 'dddddddd-0000-0000-0000-000000000003',
    criadorId: 'bbbbbbbb-0000-0000-0000-000000000001',
    executorId: null,
    categoria: 'ENTREGA',
    status: 'ABERTA',
    titulo: 'Entregar caixa de ferramentas na Rua dos Pinheiros',
    descricao: 'A entrega falhou ontem; o pacote está no ponto de custódia.',
    xpRecompensa: 69,
    // Sempre 0 — ck_missao_economia (V15). A UI ignora, e há teste garantindo que ignora.
    valorBrl: 0,
    tokensRecompensa: 23,
    poteTokens: 0,
    origemLat: -23.564,
    origemLon: -46.6934,
    destinoLat: null,
    destinoLon: null,
    pontoCustodiaId: null,
    cep: '05422030',
    logradouro: 'Rua dos Pinheiros, 500',
    bairro: 'Pinheiros',
    cidade: 'São Paulo',
    uf: 'SP',
    raioCheckinM: 50,
    pesoKg: 3.5,
    volumeL: 20,
    janelaInicio: '2026-08-08T12:00:00Z',
    janelaFim: '2026-08-09T12:00:00Z',
    criadaEm: '2026-08-07T10:00:00Z',
    aceitaEm: null,
    concluidaEm: null,
    complexidade: 'MEDIA',
    versaoFormula: 1,
    versao: 0,
    ...sobrescrever,
  };
}

/**
 * A MESMA missão como o servidor a devolve para quem NÃO participa dela.
 *
 * O backend recorta o endereço por participação: criador e executor veem tudo, os demais recebem
 * `cep`/`logradouro` nulos e coordenada com 3 casas (~110 m). É o que `GET /missoes`,
 * `GET /missoes/proximas` e o detalhe de missão alheia devolvem.
 *
 * Existe como fixture SEPARADA em vez de substituir `missao()` porque as duas formas são reais e o
 * app precisa aguentar ambas — e porque trocar a de cima quebraria dezenas de testes de uma vez, sem
 * que nenhum deles fosse sobre isto. Sem esta fixture, a suíte inteira passava verde contra um
 * contrato que o servidor não devolve mais.
 */
export function missaoDeTerceiro(sobrescrever: Partial<MissaoResponse> = {}): MissaoResponse {
  return missao({
    cep: null,
    logradouro: null,
    origemLat: -23.564,
    origemLon: -46.693,
    ...sobrescrever,
  });
}

export function pagina<T>(
  conteudo: T[],
  sobrescrever: Partial<PaginaResponse<T>> = {},
): PaginaResponse<T> {
  return {
    conteudo,
    pagina: 0,
    tamanho: 20,
    totalElementos: conteudo.length,
    totalPaginas: conteudo.length === 0 ? 0 : 1,
    primeira: true,
    ultima: true,
    ...sobrescrever,
  };
}

export function proxima(
  distanciaM: number,
  sobrescrever: Partial<MissaoResponse> = {},
): MissaoProximaResponse {
  return { missao: missao(sobrescrever), distanciaM };
}

export const CARTEIRA: CarteiraResponse = {
  id: 'cccccccc-0000-0000-0000-000000000002',
  usuarioId: USUARIO.id,
  saldoBrl: 0,
  saldoTokens: 41,
};

export const LANCAMENTO: LancamentoResponse = {
  id: 'eeeeeeee-0000-0000-0000-000000000001',
  sinal: 'CREDITO',
  motivo: 'RECOMPENSA_MISSAO',
  valorBrl: 0,
  valorTokens: 23,
  missaoId: 'dddddddd-0000-0000-0000-000000000003',
  contraparteCarteiraId: null,
  mensagem: null,
  saldoAposBrl: 0,
  saldoAposTokens: 41,
  criadoEm: '2026-08-07T18:30:00Z',
};

export const TRIBO: TriboResponse = {
  id: 'aaaaaaaa-0000-0000-0000-000000000001',
  nome: 'Tribo Pinheiros',
  bairro: 'Pinheiros',
  centroLat: -23.561807,
  centroLon: -46.687173,
};

/**
 * Perfil da Alice, com os mesmos números do seed do backend.
 *
 * `nivel: 2` para `xp: 320` é o valor DERIVADO pela fórmula. O seed grava 3 na coluna cache, e a
 * divergência é proposital — se este fixture dissesse 3, o teste da tela deixaria de exercitar o
 * comportamento que o backend garante.
 */
export const PERFIL: PerfilResponse = {
  id: USUARIO.id,
  nome: 'Alice Ferreira',
  email: 'alice@omnitribo.dev',
  handle: 'alice',
  papel: 'USUARIO',
  tribo: TRIBO,
  xp: 320,
  nivel: 2,
  xpNivelAtual: 100,
  xpProximoNivel: 400,
  streak: 7,
  conquistas: [
    {
      codigo: 'INICIANTE',
      titulo: 'Primeiro passo',
      descricao: 'Conclua a primeira missão do bairro.',
      conquistada: true,
      progresso: 1,
      meta: 1,
    },
    {
      codigo: 'VIZINHO_PRESENTE',
      titulo: 'Vizinho presente',
      descricao: 'Acumule experiência ajudando por perto.',
      conquistada: false,
      progresso: 320,
      meta: 500,
    },
  ],
};

export const CONSENTIMENTOS: ConsentimentoResponse[] = [
  {
    tipo: 'LOCALIZACAO',
    concedido: true,
    versaoTexto: '2026-08-01',
    registradoEm: '2026-07-10T02:00:00Z',
  },
  // Concedida e depois revogada no seed: vale a linha mais recente.
  {
    tipo: 'NOTIFICACAO',
    concedido: false,
    versaoTexto: '2026-08-01',
    registradoEm: '2026-08-07T02:00:00Z',
  },
  {
    tipo: 'TERMOS',
    concedido: true,
    versaoTexto: '2026-08-01',
    registradoEm: '2026-07-10T02:00:00Z',
  },
];

export function alerta(sobrescrever: Partial<AlertaResponse> = {}): AlertaResponse {
  return {
    id: 'dddddddd-0000-0000-0000-000000000002',
    tipo: 'MISSAO_CONCLUIDA',
    titulo: 'Recompensa creditada',
    corpo: 'Missão concluída. A recompensa já está na sua carteira.',
    missaoId: null,
    lido: false,
    criadoEm: '2026-08-08T02:00:00Z',
    ...sobrescrever,
  };
}

/** Uma lida e duas pendentes — casa com o contador padrão de 2 não lidas. */
export const ALERTA_PAGINA: PaginaResponse<AlertaResponse> = pagina([
  alerta({ id: 'dddddddd-0000-0000-0000-000000000003', titulo: 'Recompensa creditada' }),
  alerta({
    id: 'dddddddd-0000-0000-0000-000000000002',
    corpo: 'Missão concluída e recompensa creditada. Você subiu para o nível 2.',
  }),
  alerta({ id: 'dddddddd-0000-0000-0000-000000000001', lido: true, titulo: 'Já lida' }),
]);

export const PONTO_CUSTODIA: PontoCustodiaResponse = {
  id: 'cccccccc-0000-0000-0000-000000000001',
  codigo: 'LM-PIN-001',
  tipo: 'LOJA',
  apelido: 'Leroy Merlin Pinheiros',
  lat: -23.564,
  lon: -46.6934,
  capacidade: 50,
  ocupacao: 3,
  distanciaM: 0,
};

export const CLIMA: ClimaResponse = {
  temperaturaC: 21.8,
  sensacaoC: 21.6,
  codigo: 1,
  descricao: 'Parcialmente nublado',
  medidoEm: '2026-08-09T02:00:00Z',
};

export const ENDERECO: EnderecoResponse = {
  cep: '01001000',
  logradouro: 'Praça da Sé',
  bairro: 'Sé',
  cidade: 'São Paulo',
  uf: 'SP',
};

export const PREVIA: PreviaRecompensaResponse = {
  xpRecompensa: 69,
  tokensRecompensa: 23,
  complexidade: 'MEDIA',
  versaoFormula: 1,
};

/** Corpo RFC 9457 como o backend o emite, incluindo `traceId`. */
export function problema(
  type: string,
  status: number,
  detail: string,
  extra: Record<string, unknown> = {},
) {
  return {
    type: `https://omnitribo.dev/problemas/${type}`,
    title: 'Erro',
    status,
    detail,
    instance: '/api/v1/teste',
    traceId: '11111111-2222-3333-4444-555555555555',
    ...extra,
  };
}
