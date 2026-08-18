const presetExpo = require('jest-expo/jest-preset');

/**
 * Testes de integração contra o backend em execução.
 *
 * **Roda em ambiente Node, e NÃO sob o preset do React Native.** O motivo é concreto: o ambiente do
 * jest-expo instala um `XMLHttpRequest` e um `fetch` de mentira, e o axios, ao encontrá-los, escolhe
 * um adaptador que não faz I/O nenhum. O sintoma engana — toda chamada volta como `semRede`, igual
 * ao que o app mostraria com o backend desligado. Sob MSW isso nunca aparece, porque o interceptador
 * substitui o transporte por um que responde.
 *
 * Em ambiente Node o axios resolve para o build de servidor e fala de verdade. O preço é um stub de
 * `react-native`, que a camada `src/api` toca em um único ponto (`Platform.OS`).
 *
 * Também não carrega `jest.setup.ts`: aquele instala o MSW, e um teste de integração com a rede
 * interceptada testaria o mock, não a API.
 *
 * @type {import('jest').Config}
 */
module.exports = {
  testEnvironment: 'node',
  transform: presetExpo.transform,
  transformIgnorePatterns: ['node_modules/(?!(expo|@expo/.*|zustand))'],
  moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'mjs', 'json', 'node'],
  moduleNameMapper: {
    '^react-native$': '<rootDir>/src/testes/reactNativeStub.js',
    '^@/(.*)$': '<rootDir>/src/$1',
  },
  setupFilesAfterEnv: ['<rootDir>/jest.e2e.setup.ts'],
  testMatch: ['**/*.e2e.test.ts'],
  // A rede real é mais lenta que um mock, e o primeiro login paga o custo do Argon2 no servidor.
  testTimeout: 30000,
  globals: { __DEV__: true },
};
