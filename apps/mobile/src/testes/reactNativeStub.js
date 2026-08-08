/**
 * Stub mínimo de `react-native` para o teste de integração, que roda em ambiente Node.
 *
 * A camada `src/api` toca o React Native num único ponto — `Platform.OS`, em `baseUrl.ts`, e só no
 * caminho de fallback. Carregar o RN inteiro num ambiente Node para isso não é possível, e o preset
 * do RN não faz rede de verdade (nem XHR nem fetch), que é justamente o que este teste precisa.
 */
module.exports = {
  Platform: { OS: 'android', select: (opcoes) => opcoes.android ?? opcoes.default },
};
