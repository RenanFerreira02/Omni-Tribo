// https://docs.expo.dev/guides/using-eslint/
const { defineConfig } = require('eslint/config');
const expoConfig = require('eslint-config-expo/flat');
const prettier = require('eslint-plugin-prettier/recommended');

module.exports = defineConfig([
  expoConfig,
  prettier,
  {
    ignores: ['dist/*', 'node_modules/*', '.expo/*', 'coverage/*'],
  },
  {
    rules: {
      // "NENHUM hex literal fora de src/theme/tokens.ts" (apps/mobile/CLAUDE.md) deixa de ser
      // disciplina e vira erro de lint. Sem isto a regra sobrevive só enquanto alguém lembrar dela
      // na revisão — e o primeiro `color: '#fff'` copiado de um exemplo passa despercebido.
      // O override abaixo reabre a exceção para src/theme/, o único lugar que pode declarar cor.
      'no-restricted-syntax': [
        'error',
        {
          selector: 'Literal[value=/^#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/]',
          message:
            'Cor literal é proibida fora de src/theme/tokens.ts. Importe de "@/theme" — ver apps/mobile/CLAUDE.md.',
        },
      ],
    },
  },
  {
    files: ['src/theme/**/*.ts'],
    rules: {
      'no-restricted-syntax': 'off',
    },
  },
  {
    // app.config.ts declara cores de ícone e splash, que são consumidas pelo build nativo e não
    // pelo runtime do React — não há como importá-las de src/theme, que o Metro só resolve depois.
    files: ['app.config.ts'],
    rules: {
      'no-restricted-syntax': 'off',
    },
  },
]);
