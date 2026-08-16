# Comparativo de tecnologia mobile — Flutter × Kotlin nativo × React Native

**Data:** 2026-08-16 · **Fase:** F13

## Nota de método, antes da tabela

Este documento **não** é uma comparação de mercado escrita de memória. Cada linha aponta para
artefato que existe em disco ou para um problema que este projeto enfrentou. Onde não há evidência,
está escrito que não há.

Três avisos que mudam como a tabela deve ser lida:

**1. Os escopos comparados NÃO são equivalentes.** O app Kotlin (`Omni-Tribo-PBL-Kotlin`) é um
protótipo **offline-first**: Room local, nenhuma autenticação, e a única chamada de rede é o ViaCEP.
O app React Native deste repositório é **cliente de uma API real** — sessão com refresh rotativo,
geolocalização validada no servidor, idempotência nas operações de valor. Comparar linhas de código entre eles mediria a
diferença de escopo, não de tecnologia. Onde a comparação é injusta, a tabela diz.

**2. Flutter é a perna mais fraca.** Houve um protótipo Flutter, descartado antes desta
reconstrução, e **não há código dele em disco** — nada a medir. O que se sabe dele está registrado
como decisão no `CLAUDE.md`: distância e valor eram `String`, não havia autenticação, e aceitar
missão creditava a recompensa imediatamente. Isso diz respeito à **qualidade daquele protótipo**,
não a Flutter como tecnologia, e seria desonesto usá-lo como argumento contra o framework. As
afirmações sobre Flutter abaixo vêm de propriedades conhecidas da plataforma, e estão marcadas como
**sem evidência local**.

**3. Não existe comparação anterior a "atualizar".** O documento da Fase 4
(`documentacao/Omni-Tribo - Documentação.pdf`) é um PETI — SWOT, 5 Ps, TOGAF, COBIT — e **não
contém escolha de stack mobile**: as palavras Flutter, Kotlin e React Native não aparecem nele. Este
comparativo é escrito do zero. Ver [`DIVERGENCIAS-DOCUMENTACAO.md`](DIVERGENCIAS-DOCUMENTACAO.md).

## Os artefatos medidos

| | React Native / Expo | Kotlin nativo | Flutter |
|---|---|---|---|
| Onde | `apps/mobile/` deste repositório | repositório `Omni-Tribo-PBL-Kotlin` | — |
| Versões | Expo SDK 57 · RN 0.86.2 · TypeScript 6 | Kotlin 2.0.21 · Compose BOM 2024.09 · Room 2.7.2 · Retrofit 2.11 | — |
| Telas | 16 arquivos de rota — 12 telas e 4 layouts | 7 telas Compose | — |
| Linhas (sem teste) | 8.386 | 2.265 | — |
| Testes automatizados | **179**, em 14 suítes | **1**, e é o `ExampleUnitTest` gerado pelo template | — |
| Integração | API própria (44 endpoints no backend), JWT + refresh rotativo | Room local; rede só para ViaCEP | — |

O contraste de testes é o dado mais forte da tabela, e **não é sobre a linguagem**: é sobre o custo
de montar o ambiente de teste. Falarei disso na conclusão.

## Critério a critério

| Critério | React Native / Expo | Kotlin nativo | Flutter |
|---|---|---|---|
| **Alcance por base de código** | iOS + Android + web a partir de um código. A web foi usada de verdade aqui, como caminho de demonstração sem emulador | Android apenas. iOS exigiria um segundo app | iOS + Android + web *(sem evidência local)* |
| **Tempo até a primeira tela** | minutos: `npm start` e QR no Expo Go, **sem build nativo** | exige Android Studio, SDK, AVD ou aparelho, e um build Gradle | comparável a RN *(sem evidência local)* |
| **Recurso nativo** | via módulos do Expo (`expo-location`, `expo-secure-store`). Excelente **quando o módulo existe** — ver a linha de mapa | acesso direto e sem intermediário a qualquer API do Android | plugins pub.dev *(sem evidência local)* |
| **Armazenamento seguro** | `expo-secure-store` no aparelho, mas **sem implementação web**: o módulo resolvido no browser é `export default {}` e estoura no boot. Foi preciso uma camada própria e a decisão de não persistir nada na web ([ADR 0013](adr/0013-persistencia-de-segredo-por-plataforma.md)) | Keystore do Android direto, sem camada de compatibilidade | *(sem evidência local)* |
| **Mapa** | **custou uma decisão**: `react-native-maps` foi descartado e o mapa virou WebView + Leaflet ([ADR 0012](adr/0012-mapa-por-webview-e-leaflet.md)) | Google Maps Compose, caminho de primeira classe | *(sem evidência local)* |
| **Tipagem** | TypeScript `strict` — bom, mas é tipagem sobre JavaScript: o limite do sistema (`any`, JSON não validado) exige Zod na borda | Kotlin, tipado e com nulidade no sistema de tipos | Dart, tipado *(sem evidência local)* |
| **Teste automatizado** | Jest + RTL + MSW, roda em Node, **segundos** — 179 testes em 4,7 s. Mas o ecossistema tem armadilhas caras (abaixo) | Unidade é fácil; teste de UI exige emulador (Espresso), ordens de grandeza mais lento | *(sem evidência local)* |
| **Distribuição** | Expo Go para demonstração; EAS Build para APK/IPA ([procedimento](../apps/mobile/README.md#build-de-apk-com-eas)) | APK direto do Gradle, sem serviço externo | *(sem evidência local)* |
| **Custo de saída** | alto se precisar de algo fora do Expo Go: exige *development build* e o ganho de "sem build nativo" evapora | não se aplica — já é nativo | *(sem evidência local)* |

## O que a escolha por Expo custou, concretamente

Nenhum destes é hipotético. Todos consumiram tempo neste projeto e estão documentados:

1. **`jest-expo` 57 prende o ecossistema jest em 29.** Instalar `jest` 30 (que é o `latest` do npm)
   mistura `jest-runtime` 30 com `jest-environment-node` 29 e a suíte morre em
   `this._moduleMocker.clearMocksOnScope is not a function` — erro que não menciona versão nenhuma.
2. **RNTL 14 tornou `render` e `fireEvent` assíncronos.** Sem `await`, `screen` fica vazio e todo
   `getByTestId` estoura com "`render` function has not been called".
3. **O primeiro teste de uma suíte de tela é ordens de grandeza mais caro que os outros.** O
   `react-native` exporta componentes por getters preguiçosos: o grafo de módulos só carrega no
   primeiro `render()`. Medido: **221 ms → 2110 ms** com cache frio. Foi a causa do CI do mobile
   vermelho da F9 até 2026-08-13, e passava em toda máquina local porque cache quente escondia.
4. **O ambiente do `jest-expo` não faz rede de verdade.** `XMLHttpRequest` e `fetch` são dublês, o
   que obrigou o teste de integração a rodar num segundo config (`jest.e2e.config.js`) com
   `testEnvironment: 'node'`.
5. **`react-native-maps` descartado**, mapa por WebView + Leaflet.
6. **`expo-secure-store` sem implementação web**, que quebrava no boot do browser antes da primeira
   tela.

**Três dos seis são do ecossistema de teste.** É o padrão que mais me chama atenção: o custo do
Expo aqui não apareceu em escrever a tela, apareceu em *provar* que a tela funciona.

## O que ela comprou

- **Demonstração sem cadeia de build nativa.** Numa banca, ler um QR code com o Expo Go é a
  diferença entre demonstrar e pedir desculpa por um Gradle que não compilou. É o benefício que mais
  pesou.
- **Web como plano B real.** `npm run web` funciona, e o roteiro de demonstração o usa como saída
  quando o emulador falha — algo que Kotlin nativo não oferece.
- **Ciclo de teste em segundos**, uma vez pagas as armadilhas: 179 testes em 4,7 s, contra emulador.
- **Uma base de código** para iOS e Android, com um desenvolvedor só.

## Quando eu teria escolhido diferente

**Kotlin nativo**, se o produto dependesse de execução em segundo plano confiável — rastreamento de
localização contínuo, geofencing, serviço em foreground. É exatamente o tipo de coisa que este
produto poderia querer depois (detectar que o vizinho chegou perto do ponto de custódia), e é onde a
camada do Expo cobra mais caro.

**Kotlin nativo**, também, se a entrega fosse Android-only — que é o caso do PBL ao lado. Aí o
alcance multiplataforma não vale nada e a indireção só atrapalha.

**Flutter** eu não tenho base para recomendar nem para descartar neste projeto, e prefiro dizer isso
a inventar um empate.

## Conclusão

**Para este projeto, Expo foi a escolha certa, e a razão principal não é técnica: é o custo de
demonstrar.** Um projeto acadêmico que precisa ser defendido oralmente tem um requisito que produto
comercial não tem — funcionar na frente de outras pessoas, num ambiente que não é o seu. O Expo Go
resolve isso melhor que qualquer alternativa aqui.

**A escolha cobrou o preço no lugar menos óbvio.** Não custou na UI; custou no ecossistema de teste,
onde três armadilhas distintas consumiram tempo real e uma delas manteve o CI vermelho por dias.
Quem repetir a escolha deve orçar isso.

**E a comparação tem um limite que a tabela não resolve:** o app Kotlin ao lado tem 1 teste, e o
React Native tem 179. Essa diferença não mede Kotlin contra TypeScript — mede um projeto com CI e
gates de cobertura contra um protótipo de disciplina. Atribuir isso à linguagem seria exatamente o
tipo de conclusão torcida que este documento tentou evitar.
