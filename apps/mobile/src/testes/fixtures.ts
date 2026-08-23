import type {
  AlertaResponse,
  BeneficioResponse,
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
  ResgateResponse,
  TriboResponse,
  UsuarioBuscaResponse,
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
    nivelMinimo: 1,
    // Sem avaliação de risco — o padrão da fixture, porque é o padrão das missões: só o webhook de
    // entrega falida avalia. Os testes de aviso sobrescrevem estes três explicitamente.
    multiplicadorRisco: null,
    faixaRisco: null,
    avisoRisco: null,
    versao: 0,
    ...sobrescrever,
  };
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
  multiplicadorRisco: 1.0,
};

/** Corpo RFC 9457 como o backend o emite, incluindo `traceId`. */
/**
 * Catálogo de teste, calibrado na fixture `CARTEIRA` (41 tokens) de propósito: o café custa 15
 * (alcança) e a revisão custa 60 (faltam 19). As duas metades da regra são exercitadas com o mesmo
 * saldo, sem mock por teste — mesma calibração que a versão hardcoded desta tela usava.
 */
export const BENEFICIO_ALCANCAVEL: BeneficioResponse = {
  id: '33333333-0000-0000-0000-000000000960',
  titulo: 'Um café coado e um pão na chapa',
  descricao: 'Retire no balcão apresentando o código. De segunda a sábado, até as 11h.',
  custoTokens: 15,
  tipo: 'BEM',
  parceiroId: '22222222-0000-0000-0000-000000000960',
  parceiroNome: 'Padaria Pão da Praça',
  bairro: 'Cidade Líder',
  distanciaM: 212.4,
};

export const BENEFICIO_CARO: BeneficioResponse = {
  id: '33333333-0000-0000-0000-000000000962',
  titulo: '20% de desconto na revisão da bicicleta',
  descricao: 'Desconto proporcional sobre a mão de obra. Não acumula com outras ofertas.',
  custoTokens: 60,
  tipo: 'PERCENTUAL',
  parceiroId: '22222222-0000-0000-0000-000000000961',
  parceiroNome: 'Bicicletaria do Zé',
  bairro: 'Cidade Líder',
  distanciaM: 640.1,
};

export const RESGATE: ResgateResponse = {
  id: '44444444-0000-0000-0000-000000000001',
  beneficioId: BENEFICIO_ALCANCAVEL.id,
  custoTokens: 15,
  // Alfabeto sem 0/O e sem 1/I/L — o mesmo do GeradorCodigoRetirada.
  codigoRetirada: 'CVYU5UCH',
  status: 'PENDENTE',
  criadoEm: '2026-08-22T12:00:00Z',
  utilizadoEm: null,
  saldoTokensRestante: 26,
  replay: false,
};

/** O vizinho que a busca por `@` devolve — mesma tribo da fixture `PERFIL`. */
export const VIZINHO: UsuarioBuscaResponse = {
  id: 'bbbbbbbb-0000-0000-0000-000000000003',
  handle: 'marlene',
  nome: 'Marlene Souza',
  tribo: 'Tribo Pinheiros',
};

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

/**
 * Painel de impacto, com números escolhidos para serem CONFERÍVEIS de cabeça na leitura do teste:
 * 22 = 6 + 12 + 3 + 1, conversão 6/22 = 27,3%, conclusão 3/4 = 75%, custo 3 × 25 = 75.
 *
 * `criadas` (4) é MENOR que `convertidas` (6) de propósito: é a forma real do banco, onde entrega
 * falida do seed histórico aponta para missão criada por humano. A fixture antiga tinha os dois
 * iguais e escondia o caso que a tela precisa explicar.
 *
 * `pendentes` alto de propósito — é o formato do banco de desenvolvimento, onde o seed histórico
 * domina, e é o caso em que a tela precisa explicar a conversão baixa em vez de deixar concluir que
 * o bairro não responde.
 */
export const IMPACTO = {
  geradoEm: '2026-08-23T14:02:11Z',
  entregasFalidas: {
    recebidas: 22,
    convertidas: 6,
    pendentes: 12,
    recusadasPontoLotado: 3,
    recusadasSemPatrocinio: 1,
    taxaConversao: 0.2727,
  },
  missoesDeRetirada: {
    criadas: 4,
    concluidas: 3,
    taxaConclusao: 0.75,
    medianaAteCheckinSegundos: 8100,
    amostraMediana: 5,
  },
  custoEvitado: {
    reentregasEvitadas: 3,
    premissaCustoReentregaBrl: 25.0,
    baseBrl: 75.0,
    menos50Brl: 37.5,
    mais50Brl: 112.5,
  },
  tokens: {
    aportados: 40000,
    emCarteiras: 38200,
    emPotes: 1200,
    emCirculacao: 39400,
    resgatados: 600,
  },
};
