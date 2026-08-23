import { formatarDistancia, formatarPrazo } from '@/lib/formatar';

const AGORA = new Date('2026-08-24T12:00:00Z');

/** Daqui a N minutos, em ISO. */
function em(minutos: number): string {
  return new Date(AGORA.getTime() + minutos * 60000).toISOString();
}

describe('formatarPrazo', () => {
  it('minutos enquanto a decisão é urgente', () => {
    expect(formatarPrazo(em(40), AGORA)).toBe('termina em 40 min');
  });

  it('horas quando já não cabe em minutos', () => {
    // "termina em 180 min" é exato e ilegível. A granularidade cai conforme a urgência.
    expect(formatarPrazo(em(180), AGORA)).toBe('termina em 3 h');
  });

  it('dias quando falta muito, com singular e plural', () => {
    expect(formatarPrazo(em(60 * 24), AGORA)).toBe('termina em 1 dia');
    expect(formatarPrazo(em(60 * 24 * 3), AGORA)).toBe('termina em 3 dias');
  });

  it('passado vira "encerrada", nunca um número negativo', () => {
    // O radar só devolve missão aberta, mas a tela pode estar parada há um tempo — e "termina em
    // -30 min", lido em voz alta, não significa nada.
    expect(formatarPrazo(em(-30), AGORA)).toBe('encerrada');
    expect(formatarPrazo(em(0), AGORA)).toBe('encerrada');
  });

  it('data inválida não quebra a lista', () => {
    expect(formatarPrazo('não é data', AGORA)).toBe('—');
  });
});

describe('formatarDistancia', () => {
  it('metros abaixo de 1 km, quilômetros acima', () => {
    expect(formatarDistancia(180.4)).toBe('180 m');
    expect(formatarDistancia(2400)).toBe('2,4 km');
    // Uma casa até 10 km, nenhuma acima: "12,4 km" a pé não informa mais que "12 km".
    expect(formatarDistancia(12400)).toBe('12 km');
  });

  it('valor impossível vira travessão em vez de "NaN m"', () => {
    expect(formatarDistancia(Number.NaN)).toBe('—');
    expect(formatarDistancia(-1)).toBe('—');
  });
});
