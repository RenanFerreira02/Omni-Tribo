# 0013 — Persistência de segredo por plataforma: nada é gravado na web

**Data:** 2026-08-09
**Status:** Aceito

---

## Contexto

O app roda hoje em três plataformas: Android, iOS e — desde que `app.config.ts` não declara
`platforms` e o `react-native-web` está instalado — também no browser, por `npm run web`. A web
nunca foi validada; o `apps/mobile/README.md` só documentava o caminho do Expo Go.

Ao abrir o app no navegador, ele quebra no boot:

```
ExpoSecureStore.default.deleteValueWithKeyAsync is not a function
```

A causa é direta e não é bug do nosso código: **`expo-secure-store` não tem implementação web.** O
módulo nativo resolvido no bundle web é literalmente

```js
// node_modules/expo-secure-store/build/ExpoSecureStore.web.js
export default {};
```

e `SecureStore.js` chama `getValueWithKeyAsync`, `setValueWithKeyAsync` e `deleteValueWithKeyAsync`
sem nenhuma guarda. As três estouram. A única API defensiva da biblioteca é `isAvailableAsync()`,
que devolve `false` na web — e que nós não chamávamos.

A mensagem que o usuário vê aponta para a linha **errada**, e isso importa para entender o desenho
adotado. A cadeia real é:

1. `app/_layout.tsx` chama `restaurarSessao()` no boot;
2. `restaurarSessao` chama `lerRefreshPersistido()` → `getItemAsync` **estoura**;
3. o `catch` chama `estado.encerrar()` → `deleteItemAsync` **estoura de novo, dentro do catch**;
4. essa segunda exceção escapa como unhandled rejection e substitui a primeira.

O erro reportado descreve o passo 3. O defeito estava no passo 2. Um caminho de recuperação que
produz exceção nova destrói o diagnóstico do problema que deveria estar resolvendo.

A pergunta de fundo, então, não é "como fazer a web parar de quebrar" — é **onde o refresh token
mora quando a plataforma não tem cofre**. O `CLAUDE.md` da raiz já traz a regra dura: "credencial em
expo-secure-store, NUNCA AsyncStorage". O motivo declarado é que AsyncStorage grava em claro. No
browser, `localStorage` e `sessionStorage` gravam em claro pelo mesmo motivo — a regra não muda de
valor só porque a plataforma mudou de nome.

---

## Decisão

Adotamos um **ponto único de persistência de segredo**, `apps/mobile/src/lib/armazenamentoSeguro.ts`,
que decide por `Platform.OS` em runtime:

- **nativo** → `expo-secure-store` (Android Keystore / iOS Keychain), exatamente o comportamento de
  antes;
- **web** → um `Map` no escopo do módulo. **Nada toca o disco do browser.** A sessão vive enquanto a
  aba viver; recarregar a página desloga.

Nenhum outro módulo do app fala com `expo-secure-store` direto. Os dois que falavam —
`src/stores/sessao.ts` (refresh token) e `src/features/onboarding/visto.ts` (flag de onboarding) —
passaram a usar o wrapper.

Duas decisões menores, que existem por causa do passo 3 acima:

- `apagarSeguro` **nunca lança**. Apagar credencial é sempre operação de limpeza, e todo chamador já
  está num caminho de falha; deixá-la explodir é o que mascara o erro original.
- `restaurarSessao` envolve a limpeza no seu próprio `try`, pela mesma razão, mesmo agora que o
  wrapper torna a falha improvável.

A escolha de `Platform.OS` em runtime, em vez de um arquivo `armazenamentoSeguro.web.ts` resolvido
pelo Metro, é deliberada e o motivo é testabilidade: o preset do jest-expo declara
`platforms: [android, ios, native]`, então a variante web nunca seria carregada por teste nenhum — o
caminho que quebrou em produção ficaria sem assertion. Com a decisão em runtime, os dois caminhos
estão cobertos por `src/lib/__tests__/armazenamentoSeguro.test.ts`, inclusive a assertion de que o
caminho web **não chama** `expo-secure-store`.

---

## Consequências

**Positivas:**

- O app abre e funciona no browser, o que dá um caminho de demonstração que não depende de emulador,
  AVD nem cabo — relevante para banca.
- A regra "credencial nunca em armazenamento em claro" continua valendo em todas as plataformas, sem
  exceção e sem asterisco.
- Existe agora um lugar único para mexer em persistência de segredo. A próxima plataforma, ou uma
  eventual troca do `expo-secure-store`, é um arquivo.
- O anti-padrão que mascarou o diagnóstico está corrigido nos dois pontos: no wrapper e no chamador.

**Negativas / trade-offs:**

- **Na web, recarregar a página desloga.** É o custo consciente da decisão, não um defeito a
  reportar. Precisa estar documentado onde quem testa vai ler — está no `apps/mobile/CLAUDE.md`.
- O comportamento passa a divergir entre plataformas, então "funciona na web" deixa de ser prova de
  que funciona no aparelho para tudo que envolva sessão persistida. O teste manual no Expo Go
  continua obrigatório.
- `Platform.OS` em runtime carrega o `expo-secure-store` no bundle web mesmo sem usá-lo. O custo é
  alguns KB de um módulo que é `{}` na web — irrelevante perto de perder a cobertura de teste.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| `localStorage` na web | Persistiria a sessão entre reloads, que é a única vantagem real. Grava **em claro** um refresh token que vale 30 dias: uma falha de XSS deixa de ser roubo de uma tela e passa a ser a conta inteira, por 30 dias, sem o usuário notar. Trocar a propriedade de segurança que a regra do projeto protege por conveniência de demonstração é o pior negócio dos três. |
| `sessionStorage` na web | Mesma exposição a XSS do `localStorage` — a diferença é só o tempo de vida da aba, que é exatamente o que o `Map` em memória já dá. Paga o custo de segurança sem comprar nada. |
| Não suportar a web (`platforms: ['ios','android']`) | Honesto e mais simples, e foi considerado a sério. Descartado porque a web é o caminho de demonstração que não depende de AVD nem de aparelho, e o custo de mantê-la é um arquivo de 40 linhas. |
| Guardar por `SecureStore.isAvailableAsync()` em cada chamada | Resolve o crash sem decidir nada sobre onde o segredo mora — sobraria um app que na web nunca persiste e nunca diz por quê, com a checagem espalhada por dois módulos. Trata o sintoma; este ADR existe para registrar a causa. |

---

## Relação com outros ADRs

O [ADR 0003](./0003-react-native-expo.md) já anotava, entre os riscos aceitos, que "`expo-secure-store`
tem API ligeiramente diferente entre plataformas; requer atenção nos testes". A diferença acabou não
sendo de API, e sim de existência. Este ADR fecha aquele ponto em aberto.
