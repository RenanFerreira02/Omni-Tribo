import { acoesDisponiveis, papelNaMissao, type PapelNaMissao } from '../acoes';
import type { StatusMissao } from '@/api/tipos';

/**
 * A matriz (status × papel) do botão contextual do detalhe.
 *
 * Testada aqui, e não pela tela, porque é dado puro: nove status × três papéis são 27 casos, e
 * renderizar a tela 27 vezes trocaria segundos de suíte por nenhuma informação a mais. O teste da
 * TELA cobre que ela consome esta tabela.
 */
describe('tabela de ações da missão', () => {
  const TODOS_STATUS: StatusMissao[] = [
    'RASCUNHO',
    'ABERTA',
    'ACEITA',
    'EM_ANDAMENTO',
    'AGUARDANDO_CONFIRMACAO',
    'EM_DISPUTA',
    'CONCLUIDA',
    'CANCELADA',
    'EXPIRADA',
  ];
  const TODOS_PAPEIS: PapelNaMissao[] = ['CRIADOR', 'EXECUTOR', 'TERCEIRO'];

  /**
   * O invariante que motivou a extração: antes, quatro dos nove status deixavam a tela MUDA — sem
   * botão e sem explicação. Aqui isso é impossível por construção.
   */
  it.each(TODOS_STATUS)('%s: todo papel recebe ação ou explicação, nunca nada', (status) => {
    for (const papel of TODOS_PAPEIS) {
      const estado = acoesDisponiveis(status, papel);
      const temAlgoADizer = estado.acoes.length > 0 || estado.explicacao !== null;
      expect(temAlgoADizer).toBe(true);
    }
  });

  it.each(TODOS_STATUS)('%s: explicação e ações são mutuamente exclusivas', (status) => {
    for (const papel of TODOS_PAPEIS) {
      const estado = acoesDisponiveis(status, papel);
      if (estado.acoes.length > 0) expect(estado.explicacao).toBeNull();
    }
  });

  it('toda ação irreversível traz o texto do diálogo de confirmação', () => {
    for (const status of TODOS_STATUS) {
      for (const papel of TODOS_PAPEIS) {
        for (const acao of acoesDisponiveis(status, papel).acoes) {
          // Marcar como irreversível sem texto renderizaria um diálogo vazio — o usuário confirmaria
          // uma ação sem volta sem ler o que ela faz.
          if (acao.irreversivel) expect(acao.confirmacao?.mensagem).toBeTruthy();
          else expect(acao.confirmacao).toBeUndefined();
        }
      }
    }
  });

  // ─── Casos por status, do ponto de vista de quem age ───────────────────────────────────────

  it('RASCUNHO: só o criador publica', () => {
    expect(rotulos('RASCUNHO', 'CRIADOR')).toEqual(['Publicar missão', 'Cancelar missão']);
    expect(rotulos('RASCUNHO', 'TERCEIRO')).toEqual([]);
  });

  it('ABERTA: terceiro aceita, criador só cancela', () => {
    expect(rotulos('ABERTA', 'TERCEIRO')).toEqual(['Aceitar missão']);
    expect(rotulos('ABERTA', 'CRIADOR')).toEqual(['Cancelar missão']);
  });

  it('ACEITA: executor inicia ou desiste; terceiro entende que perdeu a vez', () => {
    expect(rotulos('ACEITA', 'EXECUTOR')).toEqual(['Iniciar missão', 'Desistir']);
    expect(acoesDisponiveis('ACEITA', 'TERCEIRO').explicacao).toMatch(/já aceitou/i);
  });

  it('EM_ANDAMENTO: só o executor faz check-in', () => {
    expect(rotulos('EM_ANDAMENTO', 'EXECUTOR')).toEqual(['Fazer check-in', 'Desistir']);
    expect(rotulos('EM_ANDAMENTO', 'CRIADOR')).toEqual([]);
    expect(acoesDisponiveis('EM_ANDAMENTO', 'CRIADOR').explicacao).toMatch(/check-in/i);
  });

  it('AGUARDANDO_CONFIRMACAO: o criador confirma ou contesta; o executor espera', () => {
    expect(rotulos('AGUARDANDO_CONFIRMACAO', 'CRIADOR')).toEqual([
      'Confirmar conclusão',
      'Contestar entrega',
    ]);
    expect(acoesDisponiveis('AGUARDANDO_CONFIRMACAO', 'EXECUTOR').explicacao).toMatch(
      /aguardando o criador/i,
    );
  });

  it('EM_DISPUTA: ninguém age no app — resolver é exclusivo de ADMIN', () => {
    for (const papel of TODOS_PAPEIS) {
      expect(rotulos('EM_DISPUTA', papel)).toEqual([]);
      expect(acoesDisponiveis('EM_DISPUTA', papel).explicacao).toMatch(/disputa/i);
    }
  });

  it.each(['CONCLUIDA', 'CANCELADA', 'EXPIRADA'] as StatusMissao[])(
    '%s é terminal: nenhuma ação para ninguém',
    (status) => {
      for (const papel of TODOS_PAPEIS) {
        expect(rotulos(status, papel)).toEqual([]);
      }
    },
  );

  it('CONCLUIDA diz coisas DIFERENTES para criador e executor', () => {
    // "a recompensa já está na sua carteira" só é verdade para o executor. Um texto único mentiria
    // para metade das pessoas que abrem a tela.
    expect(acoesDisponiveis('CONCLUIDA', 'EXECUTOR').explicacao).toMatch(/sua carteira/i);
    expect(acoesDisponiveis('CONCLUIDA', 'CRIADOR').explicacao).toMatch(/ao executor/i);
  });

  // ─── Derivação do papel ────────────────────────────────────────────────────────────────────

  describe('papelNaMissao', () => {
    const missao = { criadorId: 'alice', executorId: 'bob' };

    it('reconhece criador e executor', () => {
      expect(papelNaMissao(missao, 'alice')).toBe('CRIADOR');
      expect(papelNaMissao(missao, 'bob')).toBe('EXECUTOR');
      expect(papelNaMissao(missao, 'carol')).toBe('TERCEIRO');
    });

    it('sem usuário conhecido, trata como terceiro', () => {
      // Nunca CRIADOR por omissão: o padrão inseguro daria a alguém sem sessão os botões do dono.
      expect(papelNaMissao(missao, undefined)).toBe('TERCEIRO');
    });

    it('missão sem executor não faz de ninguém o executor', () => {
      expect(papelNaMissao({ criadorId: 'alice', executorId: null }, 'bob')).toBe('TERCEIRO');
    });
  });
});

function rotulos(status: StatusMissao, papel: PapelNaMissao): string[] {
  return acoesDisponiveis(status, papel).acoes.map((a) => a.rotulo);
}
