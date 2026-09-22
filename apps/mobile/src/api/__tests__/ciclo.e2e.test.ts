import { login } from '../auth';
import { buscarCarteira, listarLancamentos, transferirTokens } from '../carteira';
import { buscarPerfil } from '../perfil';
import { paraErroApi } from '../erros';
import {
  aplicarAcao,
  buscarMissao,
  criarMissao,
  missoesProximas,
  previaRecompensa,
  registrarCheckin,
} from '../missoes';
import type { CriarMissaoRequest, LoginResponse } from '../tipos';
import { useSessao } from '@/stores/sessao';

/**
 * O CICLO COMPLETO, contra o backend em execução. Não usa MSW.
 *
 * criar → publicar → aparecer no radar → aceitar com OUTRO usuário → iniciar → check-in →
 * confirmar → TOKEN creditado → transferir tokens.
 *
 * É o único teste do repositório que exercita a máquina de estados inteira com dois usuários reais,
 * contra o PostGIS de verdade. O que ele prova e nenhum outro prova:
 *
 * - que `CONCLUIDA` é o único estado que credita — o saldo do executor não se move em nenhum passo
 *   anterior, e essa era a regra que o protótipo Flutter descartado violava;
 * - que a distância do check-in é medida pelo servidor, e um ponto fora do raio é recusado com os
 *   NÚMEROS que a tela usa para orientar;
 * - que a recompensa é calculada e CONGELADA na criação, e que a prévia anuncia exatamente o que o
 *   servidor grava, sem o app ter reimplementado a fórmula;
 * - que a transferência exige mesma tribo, e que a idempotência protege contra o retry.
 *
 * <b>A missão do ciclo é uma AJUDA de SÓ XP (ADR 0035), e a escolha tem uma razão de teste.</b>
 * Desde o ADR 0025 uma AJUDA com recompensa em token exige pote financiado antes de publicar, e o
 * financiador tem de ser OUTRO membro da mesma tribo de alice — em Pinheiros, só o `admin`. Um
 * terceiro login estouraria o orçamento de 5 tentativas por minuto que a nota abaixo descreve:
 * este arquivo faz dois e `integracao.e2e.test.ts` faz outros dois. Este teste ficou VERMELHO da
 * adoção do ADR 0025 até aqui, sem ninguém notar, porque `test:e2e` fica fora do CI de propósito.
 *
 * <b>Consequência a saber:</b> o crédito em TOKEN na conclusão NÃO é exercitado aqui. Quem o cobre
 * ponta a ponta é `FinanciamentoControllerTest.cicloCompleto_financiarPublicarConcluir_conservaOsTokens`,
 * no backend, que tem os usuários que precisa sem passar por login. O que este ciclo prova sobre a
 * conclusão é o outro lado: que ela credita XP, que NÃO credita token, e que não escreve lançamento
 * nenhum quando não há o que mover.
 *
 * Fora do `npm test` de propósito: exige `make up` + `spring-boot:run`. Roda com:
 *
 *     E2E_API_URL=http://localhost:8080 npm run test:e2e
 *
 * <b>ATENÇÃO ao bloqueio de login: 5 tentativas por minuto, por conta.</b> Este arquivo faz DOIS
 * logins e `integracao.e2e.test.ts` faz outros dois — quatro, um a menos que o teto. Rodar a suíte
 * duas vezes dentro do mesmo minuto estoura o balde e o `beforeAll` falha com 429
 * `limiteRequisicoes`, o que se parece com um defeito e não é. Espere um minuto entre execuções.
 *
 * É por isso que os ids de carol e alice vêm do SEED, em vez de um `me()` após login: cada login
 * economizado é uma execução a mais antes do bloqueio.
 */

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

/** Pinheiros, na Rua Teodoro Sampaio — mesma região das missões do seed. */
const ORIGEM = { lat: -23.564, lon: -46.6934 };
/** Praça da Sé: ~6,3 km da origem. Longe o bastante para qualquer raio plausível. */
const LONGE = { lat: -23.5505, lon: -46.6333 };

const RAIO_CHECKIN_M = 50;

/**
 * Ids do seed (`V900__seed_dev.sql`), estáveis e legíveis de propósito.
 *
 * carol está na MESMA tribo de bob (Vila Madalena); alice está em Pinheiros. Esse par é o que
 * permite testar os dois lados da regra de transferência sem um terceiro login.
 */
const CAROL_ID = 'bbbbbbbb-0000-0000-0000-000000000004';
const ALICE_ID = 'bbbbbbbb-0000-0000-0000-000000000002';

descreve('ciclo completo da missão', () => {
  let alice: LoginResponse;
  let bob: LoginResponse;

  let missaoId: string;
  let tokensDaMissao: number;
  let xpDaMissao: number;
  let saldoBobAntes: number;

  /** Troca o usuário da sessão. O cliente HTTP lê o token do store a cada requisição. */
  async function comoUsuario(tokens: LoginResponse) {
    await useSessao.getState().definirSessao(tokens);
  }

  beforeAll(async () => {
    // Só DOIS logins, e é deliberado: ver a nota sobre o bloqueio de 5/min no topo do arquivo.
    alice = await login('alice@omnitribo.dev', 'Senha@123');
    bob = await login('bob@omnitribo.dev', 'Senha@123');
  }, 30_000);

  afterAll(async () => {
    await useSessao.getState().encerrar();
  });

  function corpoDaMissao(): CriarMissaoRequest {
    const agora = Date.now();
    return {
      categoria: 'AJUDA',
      titulo: 'Ciclo ponta a ponta — teste automatizado',
      descricao:
        'Missão criada pelo teste de integração para exercitar a máquina de estados inteira.',
      // Explícito e zero. O campo é @NotNull no servidor e recusado se > 0 (ADR 0009).
      valorBrl: 0,
      // AJUDA não move objeto: declara complexidade, e NÃO manda peso/volume.
      complexidade: 'MEDIA',
      origemLat: ORIGEM.lat,
      origemLon: ORIGEM.lon,
      cep: '05416000',
      logradouro: 'Rua Teodoro Sampaio',
      bairro: 'Pinheiros',
      cidade: 'São Paulo',
      uf: 'SP',
      raioCheckinM: RAIO_CHECKIN_M,
      janelaInicio: new Date(agora - 3600_000).toISOString(),
      janelaFim: new Date(agora + 2 * 24 * 3600_000).toISOString(),
      // Só XP: publica sem financiamento. Ver a nota no topo do arquivo.
      recompensaEmToken: false,
    };
  }

  // ─── 1. Prévia e criação ───────────────────────────────────────────────────────────────────

  it('1. a prévia anuncia a recompensa que o servidor vai congelar', async () => {
    await comoUsuario(alice);

    const previa = await previaRecompensa(corpoDaMissao());
    expect(previa.xpRecompensa).toBeGreaterThan(0);
    // Zero token, e o XP CHEIO: a prévia zera só a parte em moeda, depois de calcular o esforço.
    // Se o XP viesse zerado junto, a missão não valeria nada e o `semToken()` estaria zerando
    // antes da conta em vez de depois.
    expect(previa.tokensRecompensa).toBe(0);
    expect(previa.complexidade).toBe('MEDIA');
    // A versão da fórmula viaja junto: é o que permite explicar, depois, por que um crédito antigo
    // vale o que vale.
    expect(previa.versaoFormula).toBeGreaterThanOrEqual(1);

    tokensDaMissao = previa.tokensRecompensa;
    xpDaMissao = previa.xpRecompensa;
  }, 30_000);

  it('2. alice cria a missão, que nasce em RASCUNHO com a recompensa congelada', async () => {
    await comoUsuario(alice);

    const missao = await criarMissao(corpoDaMissao());
    missaoId = missao.id;

    expect(missao.status).toBe('RASCUNHO');
    // O valor congelado é EXATAMENTE o da prévia — o app não recalculou nada, e o servidor não
    // mudou de ideia entre uma chamada e outra.
    expect(missao.tokensRecompensa).toBe(tokensDaMissao);
    expect(missao.xpRecompensa).toBe(xpDaMissao);
    expect(Number(missao.valorBrl)).toBe(0);
    // A fonte é o que o app lê para saber POR QUE esta missão publica sem financiamento.
    expect(missao.fontePote).toBe('SEM_TOKEN');
  }, 30_000);

  it('3. publicar leva a ABERTA SEM pote, e a missão aparece no radar geoespacial', async () => {
    await comoUsuario(alice);
    // Sem financiamento nenhum. Antes do ADR 0035 esta linha era 422 `pote-insuficiente`, e era o
    // atrito que fez a categoria inteira ficar represada em RASCUNHO.
    const publicada = await aplicarAcao(missaoId, 'publicar');
    expect(publicada.status).toBe('ABERTA');
    expect(publicada.poteTokens).toBe(0);

    const proximas = await missoesProximas({
      lat: ORIGEM.lat,
      lon: ORIGEM.lon,
      raioMetros: 2000,
      limite: 100,
    });

    const nossa = proximas.find((item) => item.missao.id === missaoId);
    expect(nossa).toBeDefined();
    // Distância medida pelo PostGIS a partir da MESMA coordenada da origem: praticamente zero.
    expect(nossa!.distanciaM).toBeLessThan(5);
  }, 30_000);

  // ─── 2. Execução, com OUTRO usuário ────────────────────────────────────────────────────────

  it('4. bob aceita e inicia; o saldo dele NÃO se move', async () => {
    await comoUsuario(bob);
    saldoBobAntes = (await buscarCarteira()).saldoTokens;

    const aceita = await aplicarAcao(missaoId, 'aceitar');
    expect(aceita.status).toBe('ACEITA');
    expect(aceita.executorId).toBeTruthy();

    const emAndamento = await aplicarAcao(missaoId, 'iniciar');
    expect(emAndamento.status).toBe('EM_ANDAMENTO');

    // A regra que o protótipo descartado violava: aceitar NÃO credita.
    expect((await buscarCarteira()).saldoTokens).toBe(saldoBobAntes);
  }, 30_000);

  it('5. check-in LONGE é recusado, com os números que a tela usa para orientar', async () => {
    await comoUsuario(bob);

    try {
      await registrarCheckin(
        missaoId,
        { lat: LONGE.lat, lon: LONGE.lon, acuraciaM: 10, mocked: false },
        `e2e-longe-${missaoId}`,
      );
      throw new Error('o check-in fora do raio deveria ter sido recusado');
    } catch (erro) {
      const api = paraErroApi(erro);
      expect(api.tipo).toBe('checkinForaDoRaio');
      expect(api.status).toBe(422);

      // Os campos de extensão do RFC 9457: é com ELES que a tela escreve "você está a 6316 m;
      // aproxime-se para até 50 m", sem parsear o `detail`.
      if (api.tipo === 'checkinForaDoRaio') {
        expect(api.distanciaM).toBeGreaterThan(1000);
        expect(api.raioM).toBe(RAIO_CHECKIN_M);
      }
    }

    // E a missão NÃO transicionou: a recusa não consome a tentativa legítima.
    expect((await buscarMissao(missaoId)).status).toBe('EM_ANDAMENTO');
  }, 30_000);

  it('6. check-in NO LOCAL transiciona para AGUARDANDO_CONFIRMACAO — ainda sem crédito', async () => {
    await comoUsuario(bob);

    const missao = await registrarCheckin(
      missaoId,
      { lat: ORIGEM.lat, lon: ORIGEM.lon, acuraciaM: 8, mocked: false },
      `e2e-ok-${missaoId}`,
    );
    expect(missao.status).toBe('AGUARDANDO_CONFIRMACAO');

    // Presença não é conclusão: o crédito depende da confirmação humana do criador.
    expect((await buscarCarteira()).saldoTokens).toBe(saldoBobAntes);
  }, 30_000);

  it('7. o mesmo check-in repetido é REPLAY, sem gravar nada novo', async () => {
    await comoUsuario(bob);

    // Mesma chave: um retry de rede não pode virar um segundo check-in nem um 409.
    const replay = await registrarCheckin(
      missaoId,
      { lat: ORIGEM.lat, lon: ORIGEM.lon, acuraciaM: 8, mocked: false },
      `e2e-ok-${missaoId}`,
    );
    expect(replay.status).toBe('AGUARDANDO_CONFIRMACAO');
  }, 30_000);

  // ─── 3. Conclusão e crédito ────────────────────────────────────────────────────────────────

  it('8. alice confirma: CONCLUIDA é o ÚNICO estado que credita — aqui, em XP', async () => {
    await comoUsuario(bob);
    const xpAntes = (await buscarPerfil()).xp;

    await comoUsuario(alice);
    const concluida = await aplicarAcao(missaoId, 'confirmar');
    expect(concluida.status).toBe('CONCLUIDA');
    expect(concluida.concluidaEm).toBeTruthy();

    await comoUsuario(bob);
    // O executor foi pago na moeda que a missão prometeu: reputação, e o valor congelado na
    // criação. Nenhum passo anterior tinha movido este número.
    expect((await buscarPerfil()).xp).toBe(xpAntes + xpDaMissao);

    const carteira = await buscarCarteira();
    expect(carteira.saldoTokens).toBe(saldoBobAntes);
    // BRL permanece imóvel: a economia é de XP e TOKEN.
    expect(Number(carteira.saldoBrl)).toBe(0);
  }, 30_000);

  it('9. a conclusão de uma missão só-XP NÃO escreve no ledger', async () => {
    await comoUsuario(bob);
    const extrato = await listarLancamentos(0, 20);

    // A ausência é a assertion, e ela não é cosmética: um lançamento de 0 BRL e 0 token é recusado
    // pelo próprio `Movimento` no backend ("precisa mover BRL ou tokens") e viraria 500 na
    // conclusão. É também o que separa "não participou da invariante" de "conservou".
    const daMissao = extrato.conteudo.filter((l) => l.missaoId === missaoId);
    expect(daMissao).toHaveLength(0);
  }, 30_000);

  it('10. repetir o confirmar é REPLAY, e não 409 — a idempotência aqui é por ESTADO', async () => {
    await comoUsuario(alice);

    // Sem lançamento, a sondagem de replay do backend (que olha o ledger) é cega para esta missão.
    // Quem responde é o ESTADO da linha, sob o mesmo FOR UPDATE. Sem esse caminho, um retry de rede
    // devolveria "esta operação não é permitida no estado atual" a quem só perdeu a resposta.
    const replay = await aplicarAcao(missaoId, 'confirmar');
    expect(replay.status).toBe('CONCLUIDA');
  }, 30_000);

  // ─── 4. Transferência ──────────────────────────────────────────────────────────────────────

  it('11. bob transfere tokens para carol, da mesma tribo', async () => {
    await comoUsuario(bob);
    const antes = (await buscarCarteira()).saldoTokens;
    const quantia = 5;

    const resultado = await transferirTokens(CAROL_ID, quantia, `e2e-transf-${missaoId}`);
    expect(resultado.replay).toBe(false);
    expect(resultado.saldoTokensRemetente).toBe(antes - quantia);

    expect((await buscarCarteira()).saldoTokens).toBe(antes - quantia);
  }, 30_000);

  it('12. repetir a transferência com a MESMA chave é replay, não um segundo débito', async () => {
    await comoUsuario(bob);
    const antes = (await buscarCarteira()).saldoTokens;

    const replay = await transferirTokens(CAROL_ID, 5, `e2e-transf-${missaoId}`);
    expect(replay.replay).toBe(true);

    // O saldo não se moveu de novo. Sem idempotência, um retry de rede custaria 5 tokens ao usuário.
    expect((await buscarCarteira()).saldoTokens).toBe(antes);
  }, 30_000);

  it('13. transferir para OUTRA tribo é recusado com 422', async () => {
    await comoUsuario(bob);
    try {
      // alice é de Pinheiros; bob, de Vila Madalena. Token é moeda COMUNITÁRIA.
      await transferirTokens(ALICE_ID, 1, `e2e-tribo-${missaoId}`);
      throw new Error('a transferência entre tribos deveria ter sido recusada');
    } catch (erro) {
      const api = paraErroApi(erro);
      expect(api.status).toBe(422);
      expect(api.tipo).toBe('regraNegocioViolada');
      expect(api.detail).toMatch(/tribo/i);
    }
  }, 30_000);
});
