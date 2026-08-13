import { AxiosError, AxiosHeaders } from 'axios';

import { mensagemDe, paraErroApi, valeTentarDeNovo, type TipoErroApi } from '../erros';

/**
 * O contrato de erro, linha a linha.
 *
 * Cada caso aqui corresponde a uma constante de `compartilhado/api/TipoProblema` no backend.
 *
 * <b>Mas este arquivo NÃO fica vermelho se uma URI mudar lá</b>, e é importante não acreditar que
 * fica. Ele constrói as URIs a partir de literais próprios; nada na suíte mobile lê
 * `TipoProblema.java`. Uma auditoria encontrou este comentário afirmando uma defesa que não
 * existe — a mesma classe de problema que a rodada F0→F7 achou no backend.
 *
 * O que ele realmente garante: que o app TRADUZ corretamente cada URI conhecida para a variante de
 * `ErroApi`, e que uma URI desconhecida não derruba a tela. A divergência entre os dois catálogos
 * só apareceria comparando os dois arquivos — hoje isso é conferido por auditoria, não por teste.
 */

function respostaDe(corpo: unknown, status: number, headers: Record<string, string> = {}) {
  const erro = new AxiosError('erro', 'ERR_BAD_REQUEST');
  erro.response = {
    data: corpo,
    status,
    statusText: '',
    headers,
    config: { headers: new AxiosHeaders() },
  };
  return erro;
}

function problema(segmento: string, status: number, extra: Record<string, unknown> = {}) {
  return {
    type: `https://omnitribo.dev/problemas/${segmento}`,
    title: 'Erro',
    status,
    detail: 'Mensagem em português que pode mudar a qualquer revisão de copy.',
    instance: '/api/v1/teste',
    traceId: 'trace-123',
    ...extra,
  };
}

describe('paraErroApi — uma linha por URI do catálogo', () => {
  const casos: [string, number, TipoErroApi][] = [
    ['requisicao-invalida', 400, 'requisicaoInvalida'],
    ['nao-autenticado', 401, 'naoAutenticado'],
    ['acesso-negado', 403, 'acessoNegado'],
    ['nao-encontrado', 404, 'naoEncontrado'],
    ['transicao-invalida', 409, 'transicaoInvalida'],
    ['conflito-concorrencia', 409, 'conflitoConcorrencia'],
    ['regra-negocio-violada', 422, 'regraNegocioViolada'],
    ['limite-requisicoes', 429, 'limiteRequisicoes'],
    // `nao-implementado` saiu da lista junto com a URI: o backend removeu o handler de
    // `UnsupportedOperationException` (era código morto — nada o lançava) e a constante do catálogo.
    // Manter a linha aqui garantiria o mapeamento de algo que nunca chega.
    ['erro-interno', 500, 'erroInterno'],
    // As quatro do ADR 0010 — a razão de o catálogo ter sido ampliado.
    ['saque-desabilitado', 422, 'saqueDesabilitado'],
    ['checkin-fora-do-raio', 422, 'checkinForaDoRaio'],
    ['checkin-acuracia-insuficiente', 422, 'checkinAcuraciaInsuficiente'],
    ['checkin-localizacao-simulada', 422, 'checkinLocalizacaoSimulada'],
  ];

  it.each(casos)('%s → %s', (segmento, status, esperado) => {
    const erro = paraErroApi(respostaDe(problema(segmento, status), status));
    expect(erro.tipo).toBe(esperado);
    expect(erro.status).toBe(status);
    expect(erro.traceId).toBe('trace-123');
  });

  it('os quatro 422 são distinguíveis entre si, apesar do mesmo status', () => {
    const tipos = [
      'regra-negocio-violada',
      'saque-desabilitado',
      'checkin-fora-do-raio',
      'checkin-acuracia-insuficiente',
      'checkin-localizacao-simulada',
    ].map((segmento) => paraErroApi(respostaDe(problema(segmento, 422), 422)).tipo);

    // O ponto do ADR 0010: sem `type` próprio, este Set teria tamanho 1 e a tela não teria como
    // dar instruções diferentes sem parsear o `detail`.
    expect(new Set(tipos).size).toBe(5);
  });
});

describe('erros de validação', () => {
  it('expõe os campos recusados prontos para marcar o formulário', () => {
    const erro = paraErroApi(
      respostaDe(
        problema('requisicao-invalida', 400, {
          errors: [
            { campo: 'titulo', mensagem: 'Título deve ter entre 5 e 120 caracteres' },
            { campo: 'senha', mensagem: 'Senha muito curta' },
          ],
        }),
        400,
      ),
    );

    expect(erro.tipo).toBe('requisicaoInvalida');
    if (erro.tipo !== 'requisicaoInvalida') throw new Error('tipo inesperado');
    expect(erro.erros).toEqual([
      { campo: 'titulo', mensagem: 'Título deve ter entre 5 e 120 caracteres' },
      { campo: 'senha', mensagem: 'Senha muito curta' },
    ]);
  });

  it('tolera um errors malformado sem quebrar a tela', () => {
    const erro = paraErroApi(
      respostaDe(problema('requisicao-invalida', 400, { errors: [{ foo: 'bar' }, null, 7] }), 400),
    );
    if (erro.tipo !== 'requisicaoInvalida') throw new Error('tipo inesperado');
    expect(erro.erros).toEqual([]);
  });
});

describe('erros nascidos na cadeia de filtros do backend', () => {
  // SecurityConfig e RateLimitFilter escrevem o JSON à mão, ANTES do DispatcherServlet, e por isso
  // não têm traceId. São justamente os que o app mais recebe — 401 e 429.
  it('401 do SecurityConfig, sem traceId', () => {
    const erro = paraErroApi(
      respostaDe(
        {
          type: 'https://omnitribo.dev/problemas/nao-autenticado',
          title: 'Unauthorized',
          status: 401,
          detail: 'Autenticação necessária',
          instance: '/api/v1/missoes',
        },
        401,
      ),
    );
    expect(erro.tipo).toBe('naoAutenticado');
    expect(erro.traceId).toBeUndefined();
  });

  it('429 do RateLimitFilter traz retryAfter do corpo', () => {
    const erro = paraErroApi(
      respostaDe(
        {
          type: 'https://omnitribo.dev/problemas/limite-requisicoes',
          title: 'Too Many Requests',
          status: 429,
          detail: 'Limite de requisições atingido (100/min).',
          instance: '/api/v1/missoes',
          retryAfter: 60,
        },
        429,
      ),
    );
    if (erro.tipo !== 'limiteRequisicoes') throw new Error('tipo inesperado');
    expect(erro.retryAfter).toBe(60);
  });

  it('cai para o header Retry-After quando o corpo não traz o campo', () => {
    const erro = paraErroApi(
      respostaDe(problema('limite-requisicoes', 429), 429, { 'retry-after': '30' }),
    );
    if (erro.tipo !== 'limiteRequisicoes') throw new Error('tipo inesperado');
    expect(erro.retryAfter).toBe(30);
  });
});

describe('degradação', () => {
  it('URI fora do catálogo vira desconhecido, preservando status e detail', () => {
    const erro = paraErroApi(respostaDe(problema('algo-que-ainda-nao-existe', 418), 418));
    expect(erro.tipo).toBe('desconhecido');
    expect(erro.status).toBe(418);
    expect(mensagemDe(erro)).toContain('português');
  });

  it('about:blank não é confundido com um tipo conhecido', () => {
    const erro = paraErroApi(respostaDe({ type: 'about:blank', status: 500, detail: 'x' }, 500));
    expect(erro.tipo).toBe('desconhecido');
  });

  it('resposta ausente vira semRede', () => {
    const erro = paraErroApi(new AxiosError('Network Error', 'ERR_NETWORK'));
    expect(erro.tipo).toBe('semRede');
    expect(erro.status).toBe(0);
  });

  it('é idempotente: converter duas vezes devolve o mesmo objeto', () => {
    const uma = paraErroApi(respostaDe(problema('acesso-negado', 403), 403));
    expect(paraErroApi(uma)).toBe(uma);
  });
});

describe('valeTentarDeNovo', () => {
  it('só conflito de concorrência e falha de rede merecem insistência', () => {
    const casos: [string, number, boolean][] = [
      ['conflito-concorrencia', 409, true],
      ['transicao-invalida', 409, false],
      ['regra-negocio-violada', 422, false],
      ['acesso-negado', 403, false],
    ];
    for (const [segmento, status, esperado] of casos) {
      expect(valeTentarDeNovo(paraErroApi(respostaDe(problema(segmento, status), status)))).toBe(
        esperado,
      );
    }
    expect(valeTentarDeNovo(paraErroApi(new AxiosError('Network Error', 'ERR_NETWORK')))).toBe(
      true,
    );
  });
});
