import { rotaSeguraDe } from '../deepLink';

/**
 * Deep link é entrada NÃO confiável, e este é o teste que faltava junto com o arquivo.
 *
 * `app.config.ts` afirmava, num comentário, que a validação existia. Não existia: o `scheme` estava
 * registrado, o Expo Router navegava para qualquer caminho recebido, e o segmento cru descia até a
 * URL da API com o `Bearer` injetado. Cada caso abaixo é um link que antes teria navegado.
 */
describe('rotaSeguraDe', () => {
  const MISSAO = 'dddddddd-0000-0000-0000-000000000003';

  it('aceita as rotas da allowlist com parâmetro bem formado', () => {
    expect(rotaSeguraDe(`omnitribo://missao/${MISSAO}`)).toBe(`/missao/${MISSAO}`);
    expect(rotaSeguraDe('omnitribo://beneficios')).toBe('/beneficios');
  });

  it('recusa esquema de outro app', () => {
    // O caso clássico: outro app registra um link parecido e conta com o descuido.
    expect(rotaSeguraDe(`omnitribo-falso://missao/${MISSAO}`)).toBeNull();
    expect(rotaSeguraDe(`https://omnitribo.dev/missao/${MISSAO}`)).toBeNull();
    expect(rotaSeguraDe(`javascript:alert(1)`)).toBeNull();
  });

  it('recusa rota fora da allowlist', () => {
    // Nada de abrir tela de perfil, carteira ou o que existir amanhã só porque a rota existe.
    expect(rotaSeguraDe('omnitribo://carteira')).toBeNull();
    expect(rotaSeguraDe('omnitribo://perfil')).toBeNull();
  });

  /**
   * O achado que motivou o arquivo: o id ia CRU para `GET /missoes/{id}`, e axios não escapa
   * segmento de path. Um id com `/` ou `?` remonta a requisição contra outro endpoint.
   */
  it('recusa parâmetro que não é UUID — inclusive tentativa de remontar a URL da API', () => {
    expect(rotaSeguraDe('omnitribo://missao/../../admin')).toBeNull();
    expect(rotaSeguraDe('omnitribo://missao/123')).toBeNull();
    expect(rotaSeguraDe('omnitribo://missao/nao-e-uuid')).toBeNull();
    // Sem parâmetro nenhum: `/missao/` sozinho não é uma tela.
    expect(rotaSeguraDe('omnitribo://missao')).toBeNull();
  });

  it('recusa segmento extra: link que não entendemos não vira navegação', () => {
    expect(rotaSeguraDe(`omnitribo://missao/${MISSAO}/editar`)).toBeNull();
  });

  it('recusa entrada vazia ou malformada sem lançar', () => {
    expect(rotaSeguraDe(null)).toBeNull();
    expect(rotaSeguraDe(undefined)).toBeNull();
    expect(rotaSeguraDe('')).toBeNull();
    expect(rotaSeguraDe('nem url')).toBeNull();
  });
});
