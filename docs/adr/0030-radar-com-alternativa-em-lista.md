# 0030 — Radar com alternativa em lista, em vez de remendo no WebView

**Data:** 2026-08-24
**Status:** Aceito

---

## Contexto

O radar é tela central do produto e desenha duas coisas: missões próximas e pontos de custódia. Ele
é uma **WebView com Leaflet** (ADR 0012) — escolha feita porque `react-native-maps` exige chave do
Google Maps e renderiza cinza sem ela, e o projeto roda no Expo Go pelo QR.

O efeito colateral só foi medido na auditoria de acessibilidade de 2026-08-23, e foi classificado
como **A2 — BLOQUEIA**:

> O ponto de custódia só existe no mapa. `GET /pontos-custodia` é consumido só ali, e o **único**
> caminho até esses dados é tocar num quadrado dentro da WebView. Para leitor de tela ou limitação
> motora, são inalcançáveis.

A F18 resolveu o que dava para resolver por dentro — o alvo do marcador subiu de 18 para 44 pt, o
zoom deixou de exigir pinça —, e nenhuma das duas coisas ajuda quem não vê a tela.

**A pergunta desta decisão não é "como anotar o mapa". É "qual é a alternativa equivalente".**

---

## Decisão

**Uma segunda apresentação do MESMO destino de rota**, escolhida por um alternador `Mapa | Lista` na
própria tela do radar, com a preferência persistida entre sessões.

A lista traz o conteúdo do mapa em texto: **missões próximas e pontos de custódia**, cada seção
ordenada por distância crescente.

```
[ Mapa | Lista ]                  ← radiogroup, lembrado entre sessões

MISSÕES PRÓXIMAS (3)
  Entrega, 69 XP e 23 tokens, a 180 m, termina em 1 h, Retirar encomenda…
  …

PONTOS DE CUSTÓDIA (2)
  Ponto de custódia, loja, a 240 m, 47 de 50 vagas livres, Leroy Merlin Pinheiros.
```

### Uma rota, não duas (§1)

A lista **não é uma tela separada**. Duas rotas divergiriam: a correção de uma chegaria na outra
meses depois, ou nunca — e a que menos gente usa é a que fica para trás, que aqui seria justamente a
acessível. Mesmo caminho, mesmos hooks, mesma consulta; muda o que ocupa o lugar do `<MapaLeaflet>`.

**Nenhuma consulta nova, nenhum endpoint novo.** `useMissoesProximas`, `usePontosCustodiaProximos` e
`useClima` continuam exatamente onde estavam.

### A ordem vem do servidor (§2)

`ConsultasGeoespaciaisPostgis` já faz `ORDER BY distancia_m ASC` sobre `geography`, e
`MissaoProximaResponse.distanciaM` já traz os metros medidos pelo PostGIS. **O cliente não
reordena.** Recalcular aqui (Haversine e afins) daria um segundo valor, quase igual e ocasionalmente
diferente do que o mapa desenha — é a mesma razão pela qual `formatarDistancia` só formata o número
que chega, registrada no javadoc daquela função e no ADR 0007.

### Persistir é parte do requisito, não conforto (§3)

Quem depende da lista depende dela **sempre**. Sem persistência, teria de reencontrar e reacionar o
alternador a cada abertura do app, com leitor de tela, antes de conseguir usar a tela. **Uma
preferência de acessibilidade que não é lembrada é uma barreira cobrada por sessão.**

Guardada em `expo-secure-store` via `@/lib/armazenamentoSeguro` — e isto **não é segredo**. É a mesma
escolha de `features/onboarding/visto.ts`, pelo mesmo motivo: não trazer o
`@react-native-async-storage/async-storage` ao projeto por causa de uma string de cinco letras.
Consequência conhecida e aceita: **na web nada persiste** (ADR 0013), então a preferência volta ao
default a cada reload.

O default continua sendo **mapa**, para não mudar o que o usuário atual encontra.

### O rótulo é ordenado pela decisão, não pela importância do dado (§4)

`MissaoCard` passa a anunciar **categoria, recompensa, distância, prazo** — e só então título e
local. Quem navega por voz percorre a lista item a item e decide no primeiro terço da frase; o
título é o que menos separa uma missão da outra ("Levar tinta" × "Buscar encomenda" pesa menos que
"a 180 m" × "a 3 km").

**O prazo é novo, e entrou porque faltava:** sem ele, a pessoa só descobria que a janela ia fechar
depois de abrir o detalhe — uma navegação inteira para a informação que decide a escolha.
`formatarPrazo` é relativo ("termina em 40 min") e não absoluto, porque a pergunta que ele responde
é "dá tempo de ir?", não "quando foi?".

**A mudança vale nas DUAS abas**, porque é o mesmo componente. Duas descrições diferentes do mesmo
objeto é a divergência que a regra de rota única existe para evitar, um nível abaixo.

`ItemPontoCustodia` é componente próprio e anuncia **tipo, distância e ocupação**. Ponto de custódia
não tem recompensa nem prazo; encaixá-lo na forma da missão exigiria campos vazios, e um rótulo que
diz "0 XP e 0 tokens, encerrada" para um armário do bairro é pior que a assimetria. A ocupação está
lá porque decide se vale ir: ponto lotado recusa a encomenda, e isso é literalmente um dos três
desfechos do webhook (ADR 0021).

---

## Alternativas descartadas

| Alternativa | Custo — por que não |
|---|---|
| **Anotar a WebView por dentro** (`aria-*`, `role`, `alt` no HTML do Leaflet) | O `alt` que já passamos ao marcador **é descartado hoje**: `Marker._initIcon` do Leaflet só o aplica quando o elemento é `<img>`, e usamos `divIcon`, que produz `<div>`. Restaria o `title`, que é nome acessível fraco e inconsistente entre leitores. E passaríamos a manter uma árvore de acessibilidade **dentro de uma biblioteca de terceiro**, que a próxima versão dela pode reorganizar. Um mapa continua sendo conteúdo espacial: mesmo perfeitamente anotado, percorrer 40 pinos por varredura linear não é equivalente a ver o mapa. |
| **Trocar por `react-native-maps`** | Chave do Google Maps e *development build* — exatamente o bloqueio que o ADR 0012 pagou para não pagar, e o projeto se demonstra no Expo Go pelo QR. E não resolveria: mapa nativo tem a mesma natureza espacial. |
| **Sobrepor marcadores nativos invisíveis sobre a WebView** | Exigiria duplicar a projeção geográfica do Leaflet em JS para posicioná-los, e ficaria dessincronizado a cada gesto de arraste ou zoom. Uma camada de acessibilidade que mente sobre a posição é pior que a ausência dela. |
| **Mandar quem usa leitor de tela para a aba Missões** | Não cobre ponto de custódia, que é o dado exclusivo do radar. E navegação cruzada entre abas apresentada como "recurso de acessibilidade" é degradação disfarçada de solução — a pessoa perde o clima, perde os pontos, e sai da tela que queria usar. |
| **Só a lista, sem mapa** | O mapa é útil e é a tese visual do produto. Alternativa equivalente não é substituição. |

---

## Consequências

**Positivas**

- **A2 fecha.** O ponto de custódia deixa de existir só dentro de uma WebView.
- A aba Missões em "Perto de mim" e a lista do radar **não competem**: aquela é o catálogo de
  missões, esta é "o que há em volta de mim agora", com os pontos junto.
- O prazo passou a ser anunciado em toda missão do app, não só na lista.

**Negativas / trade-offs**

- **Duas apresentações para manter.** É o custo assumido: a alternativa era uma segunda rota, que
  divergiria mais rápido.
- **A lista não substitui o mapa.** Para quem enxerga e usa gesto, ver a distribuição espacial das
  missões continua sendo algo que uma lista ordenada não entrega. É equivalência de acesso à
  informação, não de experiência.
- **Na web a preferência não persiste** (ADR 0013). Conhecido e aceito.
- **O clima continua fora da lista**, renderizado acima das duas apresentações. Ele já é legível
  como texto e tem rótulo agregado desde a F18.

---

## O que esta decisão NÃO prova

**Nenhuma passada de TalkBack foi executada.** Não há `adb`, emulador nem aparelho Android nesta
máquina — `ANDROID_HOME` está vazio. É a **LACUNA L4**, aberta desde a auditoria mobile e ainda
aberta agora.

O que foi verificado é a **condição necessária**, e por teste automatizado:
`app/__tests__/radarLista.test.tsx` percorre login → lista → detalhe → aceitar consultando
**exclusivamente por papel e nome acessível**, sem um único `testID`. Se qualquer passo do caminho
perder rótulo ou papel, o teste fica vermelho. Isso prova que o percurso é alcançável por quem
navega pela árvore de acessibilidade; **não** prova que o TalkBack o lê na ordem esperada, nem que a
experiência é boa. Essas duas coisas exigem aparelho e uma pessoa.
