# 0012 — Mapa por WebView + Leaflet, em vez de `react-native-maps`

**Data:** 2026-08-08
**Status:** Aceito

---

## Contexto

A tela de mapa é o coração visual do produto: marcadores de missão por categoria, marcadores
distintos para pontos de custódia, recarga com debounce ao arrastar, card de clima e bottom sheet ao
tocar num marcador. A especificação pedia `react-native-maps` por nome.

O levantamento do repositório encontrou quatro fatos que, juntos, inviabilizam essa biblioteca no
estado atual do projeto:

1. **`react-native-maps` não roda no Expo Go.** O módulo nativo foi removido do cliente do Expo Go a
   partir do SDK 53, e este projeto está no SDK 57. Usá-lo exige um *development build*.
2. **O projeto é Expo managed puro.** Não existem `apps/mobile/android/` nem `ios/` — o
   `.gitignore` os exclui explicitamente (`# generated native folders`). Não há `eas.json`, não há
   `expo-dev-client` nas dependências, e o `README.md` documenta o fluxo como "leia o QR com o Expo
   Go".
3. **No Android, `react-native-maps` exige chave do Google Maps.** O mapa nativo do Android *é* o
   Google Maps; sem `android.config.googleMaps.apiKey` no `app.config.ts`, a tela renderiza um
   retângulo cinza. Não temos essa chave, e obtê-la é uma ação de conta Google fora do alcance do
   trabalho.
4. **A máquina de desenvolvimento não tem AVD criado** (`~/.android/avd` vazio) e `ANDROID_HOME` não
   está definido, embora o SDK exista.

O item 3 é o decisivo. Sem chave, o ciclo ponta a ponta pedido — criar missão → **ver no mapa** →
aceitar → check-in → confirmar — não seria demonstrável de forma nenhuma: o mapa apareceria cinza.

---

## Decisão

Adotamos **`react-native-webview` + Leaflet com tiles do OpenStreetMap**, encapsulado num componente
`MapaLeaflet` que expõe uma API de props em React Native. A tela de mapa não sabe que existe uma
WebView embaixo.

A comunicação é por mensagem nos dois sentidos: RN → WebView por `injectJavaScript` (mover câmera,
trocar marcadores), WebView → RN por `postMessage` (toque em marcador, região alterada). O debounce
de 500 ms na mudança de região fica do lado React Native, onde é testável.

Nenhum requisito funcional se perde: marcadores por categoria usam `coresCategoria` de
`src/theme/tokens.ts` (a regra de lint que proíbe hex literal fora do tema continua valendo, e as
cores atravessam para o HTML como parâmetro), pontos de custódia recebem marcador de forma distinta,
o card de clima consome `GET /api/v1/clima` e o toque abre a `FolhaInferior`.

**Consequência aceita:** o mapa exige internet. Isso já valeria para `react-native-maps` — todo mapa
de tiles baixa imagens de um servidor. Offline, a tela degrada para a lista/radar geoespacial que já
existe desde a F10.

**Porta de saída deliberada.** `MapaLeaflet` tem uma interface de props estável e é o único arquivo
que conhece Leaflet. Trocar por `react-native-maps` no dia em que houver chave e development build é
escrever uma segunda implementação com as mesmas props — nenhuma tela muda.

---

## Consequências

**Positivas**

- O fluxo de desenvolvimento documentado (Expo Go + QR code) continua valendo, inclusive nesta
  máquina, sem Android SDK configurado nem AVD.
- Zero chave de API, zero segredo novo — coerente com o critério que o ADR 0011 aplicou ao clima e
  ao CEP.
- Tiles do OpenStreetMap não têm cota comercial nem cobrança por carregamento de mapa.
- O ciclo ponta a ponta fica demonstrável de fato, que era o objetivo da tela.

**Negativas / trade-offs**

- **Desempenho abaixo de um mapa nativo.** Com centenas de marcadores a WebView engasga onde o
  Google Maps não engasgaria. Mitigado pelo teto de 100 itens de `/missoes/proximas` e
  `/pontos-custodia`, que já existia por outra razão.
- **Gestos passam pela WebView.** Interação de arrastar/pinçar dentro de uma `ScrollView` exige
  cuidado com conflito de gesto, que um componente nativo resolve sozinho.
- **Não é o que a especificação pediu por nome.** A divergência é deliberada e está registrada aqui.
- **Uma superfície de renderização a mais.** O conteúdo da WebView é HTML gerado por nós, sem
  entrada do usuário, mas é código executando num contexto diferente do resto do app.
- O teste de tela mocka `react-native-webview` — a suíte verifica que o componente recebe os
  marcadores certos, não que o Leaflet os desenhou. Renderização de mapa continua sendo verificação
  manual.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|---|---|
| **`react-native-maps` + development build (EAS ou `expo run:android`)** | Fiel ao pedido, e bloqueado na prática: exige uma chave do Google Maps que não temos, sem a qual o mapa renderiza cinza no Android. Somado a isso, abandonaria o fluxo Expo Go documentado no README, exigiria `expo-dev-client` + `eas.json`, e nesta máquina ainda faltariam `ANDROID_HOME` e um AVD. Continua sendo a escolha certa no dia em que a chave existir — e a interface de `MapaLeaflet` foi desenhada para essa troca. |
| **`expo-maps`** | Módulo novo do ecossistema Expo, mas com a mesma dependência de Google Maps no Android e a mesma exigência de development build. Troca a biblioteca sem remover nenhum dos dois bloqueios. |
| **Mapa estático por imagem (Static Maps API)** | Elimina a WebView e mata a tela: sem arrastar, sem zoom, sem `onRegionChange`, e o debounce de 500 ms pedido deixa de ter sentido. Além disso as APIs de mapa estático de qualidade também exigem chave. |
| **Manter só a lista/radar geoespacial** | É o que já existe desde a F10, e não atende ao pedido. O radar responde "o que está perto de mim"; o mapa responde "como isso se distribui pelo bairro", que é a leitura espacial que motiva o produto. |
| **`@gorhom/bottom-sheet` para a folha do marcador** | Fora por uma restrição já documentada do projeto: `src/components/Esqueleto.tsx` registra que Reanimated foi banido dos componentes porque quebra o Jest (`loadUnpackers`). A folha usa `Modal` + `Animated` do core do React Native. |
