import { BENEFICIOS, estadoDoResgate } from '@/features/beneficios/catalogo';

describe('estadoDoResgate', () => {
  it('sobra: alcança e não falta nada', () => {
    expect(estadoDoResgate(124, 80)).toEqual({ alcanca: true, faltam: 0 });
  });

  it('exato: o saldo igual ao custo já alcança', () => {
    // A fronteira, e ela é fechada de propósito: quem juntou exatamente o preço pode resgatar.
    expect(estadoDoResgate(80, 80)).toEqual({ alcanca: true, faltam: 0 });
  });

  it('falta: informa quanto falta', () => {
    expect(estadoDoResgate(41, 45)).toEqual({ alcanca: false, faltam: 4 });
  });

  it('nunca devolve falta negativa', () => {
    // "Faltam −76 tokens" é a forma mais rápida de transformar boa notícia em texto sem sentido.
    expect(estadoDoResgate(500, 40).faltam).toBe(0);
  });
});

describe('catálogo', () => {
  /**
   * **Guarda-corpo de PRODUTO, não de estilo.**
   *
   * O ADR 0009 §6 é explícito: se o token virasse conversível, ele *seria* dinheiro — com KYC e
   * enquadramento regulatório junto. Um benefício anunciado como "R$ 20 em compras" fixa uma cotação
   * token→real exatamente onde o produto recusa ter uma, e é a mesma razão pela qual a carteira
   * nunca imprime `R$`. Benefício é BEM ou PORCENTAGEM.
   *
   * Este teste existe porque a regra estava só em prosa, e prosa não reprova pull request.
   */
  it('nenhum benefício se anuncia em reais', () => {
    const emReais = BENEFICIOS.filter((b) =>
      /R\$|\breais\b|\breal\b/i.test(`${b.titulo} ${b.descricao}`),
    );

    expect(emReais.map((b) => b.id)).toEqual([]);
  });

  it('todo benefício custa pelo menos um token e tem parceiro nomeado', () => {
    for (const beneficio of BENEFICIOS) {
      expect(beneficio.custoTokens).toBeGreaterThan(0);
      expect(beneficio.parceiro.trim().length).toBeGreaterThan(0);
    }
  });

  it('os ids são únicos — a tela os usa como key e como testID', () => {
    expect(new Set(BENEFICIOS.map((b) => b.id)).size).toBe(BENEFICIOS.length);
  });
});
