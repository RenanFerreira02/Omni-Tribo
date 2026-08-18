# 0003 — React Native / Expo em vez de Flutter

**Data:** 2026-08-04  
**Status:** Aceito

---

## Contexto

O protótipo anterior foi construído em Flutter e descartado por razões de produto (não de stack).
A reconstrução precisava de uma escolha de framework mobile para o MVP acadêmico. As opções
principais eram manter Flutter ou migrar para React Native com Expo.

---

## Decisão

Adotamos React Native com Expo SDK 57 e TypeScript strict.  
Roteamento via Expo Router; estado remoto via TanStack Query; estado local via Zustand.

---

## Consequências

**Positivas:**
- Ecossistema JS/TS compartilhado com o conhecimento já existente no time.
- TanStack Query e Zustand são ferramentas amplamente documentadas com padrões de uso consolidados.
- Expo abstrai configuração de build nativa, reduzindo atrito em ambiente 100% local.
- Reaproveitamento de familiaridade com TypeScript do lado backend.

**Negativas / trade-offs:**
- JavaScript bridge (JSI ameniza, mas não elimina) introduz overhead em animações pesadas — não
  relevante para este domínio (formulários, listas, mapa).
- `expo-secure-store` tem API ligeiramente diferente entre plataformas; requer atenção nos testes.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Flutter (manter do protótipo) | Flutter também atenderia bem: hot reload maduro, boa performance, acesso ao backend via HTTP sem diferença. A escolha por Expo não é uma vantagem técnica absoluta — é uma decisão de familiaridade de time. Dart é menos conhecido; reaproveitamento de conhecimento JS/TS foi o fator decisivo. |
| React Native sem Expo (bare workflow) | Configuração nativa manual aumenta atrito no ambiente local sem benefício para o escopo do MVP. |
