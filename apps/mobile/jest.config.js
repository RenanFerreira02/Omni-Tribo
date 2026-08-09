const presetExpo = require('jest-expo/jest-preset');

// O transform do jest-expo casa `\.[jt]sx?$` — que NÃO inclui `.mjs`. Algumas dependências do MSW
// (rettime, entre outras) publicam só ESM com essa extensão, e sem esta linha o jest tenta executá-las
// como CommonJS e estoura com "Cannot use import statement outside a module" apontando para dentro de
// node_modules, o que não sugere em nada que o problema é a extensão do arquivo.
const babelParaJs = presetExpo.transform['\\.[jt]sx?$'];

/** @type {import('jest').Config} */
module.exports = {
  preset: 'jest-expo',
  setupFilesAfterEnv: ['<rootDir>/jest.setup.ts'],
  transform: {
    ...presetExpo.transform,
    '\\.mjs$': babelParaJs,
  },
  moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'mjs', 'json', 'node'],
  // `*.e2e.test.ts` fala com o backend de verdade e roda por `npm run test:e2e`, sob
  // jest.e2e.config.js. Fora daqui de propósito: um teste que falha porque o servidor não está de
  // pé não distingue regressão de ambiente, e no CI só produziria vermelho sem informação.
  testPathIgnorePatterns: ['/node_modules/', '\\.e2e\\.test\\.ts$'],
  // jest-expo não transpila node_modules por padrão, e boa parte do ecossistema RN — e todo o do
  // MSW — é publicada em ESM. Cada nome aqui é uma dependência que precisou ser transpilada.
  transformIgnorePatterns: [
    'node_modules/(?!((jest-)?react-native|@react-native(-community)?|expo(nent)?|@expo(nent)?/.*|@expo-google-fonts/.*|react-navigation|@react-navigation/.*|@sentry/react-native|native-base|react-native-svg|msw|@mswjs/.*|@bundled-es-modules/.*|until-async|@open-draft/.*|strict-event-emitter|outvariant|headers-polyfill|rettime|is-node-process|graphql|path-to-regexp|tough-cookie|universalify|psl|querystringify|requires-port|url-parse|cookie|type-fest|statuses))',
  ],
  collectCoverageFrom: ['src/**/*.{ts,tsx}', 'app/**/*.tsx', '!src/**/*.d.ts'],
  moduleNameMapper: {
    '^@/(.*)$': '<rootDir>/src/$1',
  },
};
