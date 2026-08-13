// https://docs.expo.dev/guides/using-eslint/
const { defineConfig } = require('eslint/config');
const expoConfig = require('eslint-config-expo/flat');
const prettier = require('eslint-plugin-prettier/recommended');

module.exports = defineConfig([
  expoConfig,
  prettier,
  {
    // `expo-env.d.ts` é GERADO pelo Expo, está no .gitignore e traz escrito no próprio corpo
    // "This file should not be edited". Sem ignorá-lo, o Prettier reclama da formatação de um
    // arquivo que ninguém pode corrigir — some no CI (clone novo não o tem) e deixa o lint local
    // vermelho para sempre, que é o pior dos dois mundos: ruído que só a máquina de quem
    // desenvolve vê.
    ignores: ['dist/*', 'node_modules/*', '.expo/*', 'coverage/*', 'expo-env.d.ts'],
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
        // A OUTRA metade da regra de cor, que faltava. O `CLAUDE.md` diz que "preenchimento usa
        // `cores`; TEXTO usa `textoAcessivel`" e que "um `color: cores.ambar` novo é regressão" —
        // mas o lint só pegava hex literal, então `cores.coral` como cor de texto passava limpo.
        // As duas violações que existiam no app estavam exatamente nesta metade descoberta, e uma
        // delas era o componente por onde passa TODO erro da interface.
        //
        // Os quatro tokens abaixo reprovam em WCAG AA como texto: tinta50 3,54:1, ambar 3,36:1,
        // coral 3,20:1, verdePrimario 2,7:1. Cada um tem equivalente em `textoAcessivel` (ou
        // `verdeEscuro`), com o mesmo matiz escurecido até o limiar.
        {
          selector:
            'Property[key.name="color"] > MemberExpression[object.name="cores"][property.name=/^(ambar|coral|tinta50|verdePrimario)$/]',
          message:
            'Este token reprova em contraste como TEXTO. Use textoAcessivel.* (ou cores.verdeEscuro) — ver apps/mobile/CLAUDE.md.',
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
