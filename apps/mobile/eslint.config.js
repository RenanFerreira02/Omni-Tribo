// https://docs.expo.dev/guides/using-eslint/
const { defineConfig } = require('eslint/config');
const expoConfig = require('eslint-config-expo/flat');
const prettier = require('eslint-plugin-prettier/recommended');
const a11y = require('eslint-plugin-react-native-a11y');

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
    /**
     * ACESSIBILIDADE VIRA FRONTEIRA DE LINT, e não boa vontade.
     *
     * A F12 resolveu contraste; a semântica ficou por conta de quem lembrasse. Neste projeto o que
     * não é testado vira comentário desatualizado — a auditoria de 2026-08-23 achou dois casos
     * exatos disso: `MapaLeaflet` prometendo uma rota textual equivalente que não cobria pontos de
     * custódia, e `mobile-completo.md` afirmando um `accessibilityRole="image"` que não existia no
     * arquivo. Regra de lint não deixa comentário e código divergirem.
     *
     * As regras são ligadas UMA A UMA. Os `configs` do plugin são eslintrc antigo (`plugins` como
     * array) e não funcionam em flat config; além disso a lista explícita deixa legível o que o
     * gate cobra de fato.
     *
     * `touchables` NÃO é estendido com `Botao`/`Chip`/`MissaoCard` de propósito. Esses componentes
     * já emitem papel, rótulo e estado por dentro; listá-los obrigaria os 58 pontos de uso a
     * repetir a anotação que o componente entrega — um gate que ensina a duplicar. O plugin já
     * cobre `Pressable` (na lista default dele) e `TextInput`, que é onde a semântica pode faltar
     * de verdade.
     *
     * Fora daqui, de propósito: `has-accessibility-hint`, que exige hint SEMPRE que existe rótulo.
     * Ela obrigaria hint em `SaldoToken`, `BarraProgresso` e `IndicadorPaginas`, onde a dica é
     * ruído — hint é para quando a consequência não é óbvia pelo rótulo, e isso é julgamento
     * humano, não regra.
     */
    files: ['app/**/*.tsx', 'src/**/*.ts', 'src/**/*.tsx'],
    ignores: ['app/__tests__/**', 'src/**/__tests__/**', 'src/testes/**'],
    plugins: { 'react-native-a11y': a11y },
    rules: {
      // O GATE DE VERDADE: todo Pressable e todo TextInput precisa de papel, rótulo ou ação.
      'react-native-a11y/has-valid-accessibility-descriptors': 'error',

      // ATENÇÃO — esta regra é INERTE neste código, e ligá-la sem dizer isso seria verde por vácuo.
      // Ela só dispara quando o elemento JÁ usa `accessibilityTraits`/`accessibilityComponentType`,
      // as props depreciadas da era RN 0.56, que o app não usa em lugar nenhum. O nome engana: não
      // é "todo pressável tem props de acessibilidade", é "não misture as depreciadas com
      // accessibilityRole". Fica ligada porque é barata e trava a REGRESSÃO de alguém reintroduzir
      // as antigas; não conte com ela para cobrar anotação nova.
      'react-native-a11y/has-accessibility-props': 'error',
      'react-native-a11y/has-valid-accessibility-component-type': 'error',
      'react-native-a11y/has-valid-accessibility-states': 'error',

      // Valores válidos: erram calado em runtime, porque prop desconhecida é ignorada pelo RN.
      'react-native-a11y/has-valid-accessibility-role': 'error',
      'react-native-a11y/has-valid-accessibility-state': 'error',
      'react-native-a11y/has-valid-accessibility-value': 'error',
      'react-native-a11y/has-valid-accessibility-actions': 'error',
      'react-native-a11y/has-valid-accessibility-live-region': 'error',
      'react-native-a11y/has-valid-important-for-accessibility': 'error',

      // `accessible` agrupa os filhos num nó só; um pressável lá dentro fica inalcançável pelo
      // leitor de tela. É a armadilha exata do `MissaoCard`, que agrupa e continha um `Chip`.
      'react-native-a11y/no-nested-touchables': 'error',
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
