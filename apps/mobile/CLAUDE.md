# Mobile

- app/ só rotas do Expo Router. Tela é composição, sem lógica de negócio.
- src/features/<dominio>/ hooks de TanStack Query e lógica. src/api/ é o único lugar que fala HTTP.
- src/components/ design system, sem chamada de API. src/stores/ Zustand só para UI e sessão.
- src/theme/tokens.ts — NENHUM hex literal fora daqui.
- TypeScript strict. `any` só com comentário justificando.
- Toda chamada de API tem estado de carregando, vazio e erro tratados na UI.
- npx expo install, nunca npm install, para pacotes do ecossistema Expo.
- Antes de terminar: npm run typecheck && npm run lint && npm test, e cole a saída.
