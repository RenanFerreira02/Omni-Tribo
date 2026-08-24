# Acessibilidade — matriz de conformidade e roteiro de verificação

**Data:** 2026-08-24
**HEAD:** `d251471` (branch `develop`, com F18 e F19 mergeadas)
**Telas avaliadas:** login · radar (mapa) · radar (lista) · detalhe com check-in · carteira

---

## 0. Leia isto antes da matriz

### A passada com TalkBack NÃO foi executada

Este documento foi produzido numa máquina sem qualquer forma de rodar o app em Android:

```
$ for c in adb emulator sdkmanager avdmanager; do command -v $c || echo "$c: NÃO instalado"; done
adb: NÃO instalado
emulator: NÃO instalado
sdkmanager: NÃO instalado
avdmanager: NÃO instalado

$ echo "ANDROID_HOME=${ANDROID_HOME:-vazio}"
ANDROID_HOME=vazio

$ lsusb | grep -ci android
0
```

Portanto: **nenhuma célula desta matriz que dependa de leitor de tela está marcada CONFORME.** Elas
estão como **NÃO VERIFICADO**, e os prints a 200% estão **AUSENTES**. As seções 3 e 4 existem para
que a passada seja feita por quem tiver aparelho — é isso que este documento entrega hoje: o
instrumento, mais a parte da matriz que se mede sem tela.

Marcar CONFORME sem ter ouvido seria o defeito exato que as auditorias deste projeto existem para
achar. O `CLAUDE.md` já o nomeia: *"o que não for verificado continua declarado como não verificado —
afirmar suporte que ninguém executou é pior que a lacuna, porque impede que alguém vá conferir."*

### Os 12 critérios não são o "Anexo A do ROADMAP" — ele não existe

Procurado antes de escolher a lista:

```
$ grep -rin "anexo" --include="*.md" --include="*.ts" --include="*.tsx" --include="*.java" . | grep -v node_modules
docs/EVOLUCAO-ARQUITETURAL.md:216:## Anexo — índice das auditorias      ← sem relação
docs/EVOLUCAO-ARQUITETURAL.md:221:O anexo é **índice, não cópia**…      ← sem relação

$ find . -iname "*roadmap*" -not -path "*/node_modules/*" | wc -l
0
$ pdftotext "documentacao/Omni-Tribo - Documentação.pdf" - | grep -ci anexo
0
```

Não há ROADMAP versionado, e o PDF da entrega não menciona anexo nenhum. **Os 12 critérios abaixo são
da WCAG 2.2, nível AA, no recorte que incide sobre aplicativo móvel**, cada um com o número oficial.
A troca é deliberada e tem uma vantagem: um avaliador externo consegue conferir contra a norma, o que
um anexo interno não permitiria.

### Régua de veredito

Sem esta régua, "CONFORME" vira opinião.

| Veredito | Quando |
|---|---|
| **CONFORME** | há medição executada ou teste verde que estabelece o critério **por inteiro** |
| **NÃO CONFORME** | há medição executada que mostra a falha, **com o número** |
| **NÃO VERIFICADO** | depende de aparelho ou de percepção humana. **Não é CONFORME** |
| **NÃO APLICÁVEL** | o critério não incide naquela tela |

---

## 1. Matriz: 12 critérios × 5 telas

Legenda: **C** conforme · **NC** não conforme · **NV** não verificado · **NA** não aplicável

| # | Critério (WCAG 2.2 AA) | Login | Radar / mapa | Radar / lista | Detalhe + check-in | Carteira | Como foi medido |
|---|---|:--:|:--:|:--:|:--:|:--:|---|
| 1 | **1.3.1** Informações e relações | C | **NC** | C | C | C | Código + teste: `radiogroup` em 6 grupos de escolha única; `accessible` agrupando card e item de lista. Mapa: WebView sem árvore útil |
| 2 | **1.4.1** Uso de cor | C | C | C | C | C | Glifo por categoria (◆●▲■) + rótulo textual; sinal do extrato no `accessibilityLabel`; estado do chip em `accessibilityState` |
| 3 | **1.4.3** Contraste (mínimo) | C | C | C | C | C | 31 pares calculados sobre `tokens.ts`; pior caso 4,55:1 (chip COLETA) contra piso 4,5 — §2 |
| 4 | **1.4.4** Redimensionar texto até 200% | **NV** | **NV** | **NV** | **NV** | **NV** | Estilos permitem (0 usos de `allowFontScaling={false}`; `minHeight` em todo controle; `ScrollView` na `FolhaInferior`). **Reflow real exige tela** |
| 5 | **1.4.11** Contraste não-textual | C | **NC** | **NC** | **NC** | **NC** | Borda do chip 1,30:1, trilho do `Switch` 1,38:1, `BarraProgresso` 2,46:1 — todos abaixo de 3 — §2 |
| 6 | **2.1.1** Operável por navegação alternativa | **NV** | **NC** | **NV** | **NV** | **NV** | Mapa: só gesto sobre a WebView, sem alternativa **dentro dele** — é por isso que a lista existe (ADR 0030). Demais: alcançável por papel/rótulo em teste, mas travessia real exige leitor |
| 7 | **2.4.3** Ordem de foco | **NV** | **NV** | **NV** | **NV** | **NV** | `TituloTela` move o foco na troca de rota (`acessibilidade.test.tsx`), mas **a ordem percebida só se verifica com leitor ligado** |
| 8 | **2.4.6** Cabeçalhos e rótulos | C | **NC** | C | C | C | `TituloTela` com `accessibilityRole="header"` em toda tela e seção. Mapa não tem cabeçalho interno algum |
| 9 | **2.5.5** Tamanho do alvo (≥ 44) | C | C | C | C | C | Medido nos estilos — §2 |
| 10 | **3.3.1** Identificação de erro | C | C | NA | C | C | `CampoTexto` com erro em região viva + `role="alert"`; `Aviso` com severidade falada. Radar/lista não tem entrada de dado |
| 11 | **4.1.2** Nome, função, valor | C | **NC** | C | C | C | Gate de lint (`has-valid-accessibility-descriptors`) + 18 casos de teste. Mapa: `alt` do marcador é descartado pelo Leaflet |
| 12 | **4.1.3** Mensagens de status | C | C | C | C | C | `useAnuncio` em 5 telas + 7 regiões vivas; testes verificam a chamada — §2 |

**Contagem:** 37 C · 8 NC · 14 NV · 1 NA — 60 células.

> Vale olhar para os **14 NÃO VERIFICADO** antes dos 37 CONFORME. Eles são dois critérios inteiros
> (1.4.4 redimensionar até 200% e 2.4.3 ordem de foco) mais quase toda a linha de 2.1.1 — ou seja,
> **quase um quarto da matriz depende de alguém ligar o leitor de tela.** Um relatório que
> apresentasse só a taxa de conformidade esconderia isso.

---

## 2. As medições que sustentam a matriz

### 2.1 Contraste de texto — 1.4.3

Calculado pela fórmula da WCAG 2.1 sobre os hex de `apps/mobile/src/theme/tokens.ts`. Amostra do
pior caso de cada tela; a saída completa (31 pares) está no commit desta entrega.

| Tela | Pior par | Razão | Piso |
|---|---|---:|---:|
| Login | borda do campo (não-textual) | 3,07:1 | 3,0 |
| Login | erro do campo — `taCoral` sobre branco | 5,52:1 | 4,5 |
| Radar | chip COLETA — `taAmbar` sobre `ambarClaro` | 4,55:1 | 4,5 |
| Detalhe | Aviso atenção — `taAmbar` sobre `ambarClaro` | 4,55:1 | 4,5 |
| Carteira | rótulo — `suave` sobre branco | 4,78:1 | 4,5 |

Duas correções da F18 aparecem aqui: a aba ativa saiu de `verdePrimario` (3,39:1, reprovava) para
`verdeEscuro` (6,20:1), e o badge de avisos saiu de `coral` (3,87:1) para o mesmo `verdeEscuro`.

### 2.2 Contraste não-textual — 1.4.11, **os NÃO CONFORME**

| Elemento | Par | Razão | Piso | |
|---|---|---:|---:|---|
| Borda do chip não selecionado | `linha` sobre `papel` | **1,30:1** | 3,0 | **REPROVA** |
| Trilho do `Switch` desligado | `linha` sobre branco | **1,38:1** | 3,0 | **REPROVA** |
| Preenchimento da `BarraProgresso` | `verdePrimario` sobre `linha` | **2,46:1** | 3,0 | **REPROVA** |
| Borda do `CampoTexto` | `borda` sobre branco | 3,07:1 | 3,0 | passa |
| Preenchimento do chip selecionado | `verdeEscuro` sobre `papel` | 5,87:1 | 3,0 | passa |

São o **A20** e o **A22** de `docs/auditoria/acessibilidade-inventario.md`, deixados abertos na F18
por serem COSMÉTICO — nenhum deles perde informação, porque em todos os três há texto redundante ao
lado. **A norma reprova mesmo assim**, e a matriz registra a reprovação em vez de acomodá-la.

### 2.3 Alvo de toque — 2.5.5

Medido nos estilos, não estimado.

| Controle | Cálculo | Efetivo |
|---|---|---:|
| `Botao` primário/secundário | `minHeight: 48` | 48 |
| `Botao` variante texto | `minHeight: 44` | 44 |
| `CampoTexto` / `SeletorDataHora` | `minHeight: 48` | 48 |
| `Chip` | 8+18+8 + 2×borda = 36, `hitSlop` 6/6 | 48 |
| "Fechar" da `FolhaInferior` | lineHeight 18 + `hitSlop` 13×2 | 44 |
| `Link` de login/cadastro | 22 + `paddingVertical` 11×2 | 44 |
| `Switch` de consentimento | ~31 nativo + `hitSlop` 8×2 | ~47 |
| Marcador do mapa | `iconSize: [44, 44]`, pino de 18 centrado | 44 |
| `MissaoCard` / `ItemPontoCustodia` | `Card` com `padding: 16` + conteúdo | > 44 |

### 2.4 Mensagens de status — 4.1.3

7 `accessibilityLiveRegion="polite"` no app e `useAnuncio` em 5 telas. Cobertos por teste:

- `check-in aceito anuncia a recompensa creditada`
- `check-in recusado anuncia a instrução, com a unidade por extenso`
- `ação concluída anuncia o ESTADO NOVO, não um "pronto"`
- `a busca e a transferência são ANUNCIADAS — o leitor de tela não vê a folha mudar`
- `replay NÃO é anunciado como transferência nova`
- `falha ao alterar consentimento DEIXA DE SER SILENCIOSA`

**Ressalva que vale para toda a linha 12:** os testes provam que a API é *invocada* com o texto
certo. Que o TalkBack de fato *fale* aquilo, no momento certo, sem ser cortado por outro anúncio,
**não foi verificado**.

### 2.5 O mapa — por que quatro NC concentrados numa coluna

`MapaLeaflet` é uma WebView. Três fatos, lidos no fonte do Leaflet 1.9.4 e no nosso:

1. O `alt` que passamos ao marcador **é descartado**: `Marker._initIcon` só o aplica quando
   `icon.tagName === 'IMG'`, e usamos `divIcon`, que produz `<div>`. Sobra o `title`, que é nome
   acessível fraco e inconsistente entre leitores.
2. A `<WebView>` não tem `accessible` nem `accessibilityRole`, então o `accessibilityLabel` dela não
   fecha a árvore — no Android o TalkBack entra no conteúdo web.
3. Dentro há `<div id="mapa">` sem papel, os tiles do OSM, e o link de atribuição do OpenStreetMap —
   que é o único elemento com semântica confiável ali.

**Isto não é pendência: é a razão de a coluna "Radar / lista" existir** (ADR 0030). A decisão foi dar
uma alternativa equivalente em vez de remendar a WebView, e a matriz mostra as duas lado a lado
justamente para que a diferença não fique escondida atrás de uma média.

---

## 3. Prints a 200% de escala de fonte — **AUSENTES**

Não foi possível capturá-los: sem `adb`, sem emulador, sem aparelho. Os comandos que os produzem,
para quem rodar:

```bash
adb shell settings put system font_scale 2.0        # 200% — o máximo do Android
adb shell am force-stop host.exp.exponent           # o Expo Go precisa reiniciar para reler a escala

# uma por tela, depois de navegar até ela:
adb exec-out screencap -p > docs/qualidade/prints/200-login.png
adb exec-out screencap -p > docs/qualidade/prints/200-radar-mapa.png
adb exec-out screencap -p > docs/qualidade/prints/200-radar-lista.png
adb exec-out screencap -p > docs/qualidade/prints/200-detalhe-checkin.png
adb exec-out screencap -p > docs/qualidade/prints/200-carteira.png

adb shell settings put system font_scale 1.0        # devolver ao normal
```

| Tela | Print | O que conferir |
|---|---|---|
| Login | *ausente* | Campos e botão inteiros na tela; o `Link` não cortado |
| Radar / mapa | *ausente* | Alternador legível; o mapa não empurra a barra de abas |
| Radar / lista | *ausente* | Título de seção e itens sem truncar; a lista rola |
| Detalhe + check-in | *ausente* | Botões de ação alcançáveis; `Aviso` de check-in completo |
| Carteira | *ausente* | **A folha de transferência inteira, rolando até o botão** — era o A6 |

---

## 4. Roteiro reproduzível

### 4.1 Ambiente — **preencher ao executar**

| | |
|---|---|
| Aparelho / AVD | ` ` |
| Versão do Android | ` ` |
| Versão do TalkBack | ` ` |
| Versão do Expo Go | ` ` |
| Commit do app | `d251471` |
| Data da passada | ` ` |
| Quem executou | ` ` |

> Estes campos ficam **em branco de propósito**. São o que torna a passada reproduzível e comparável
> com a próxima; preenchê-los sem ter executado destruiria o valor do documento inteiro.

### 4.2 Preparar

```bash
# 1. SDK e emulador (a máquina de referência tem /dev/kvm, mas não tinha SDK)
sdkmanager "platform-tools" "emulator" "system-images;android-34;google_apis;x86_64"
avdmanager create avd -n omnitribo -k "system-images;android-34;google_apis;x86_64"
emulator -avd omnitribo &

# 2. App
cd apps/mobile && npm start          # ler o QR com o Expo Go, ou `npm run android`

# 3. TalkBack: Configurações → Acessibilidade → TalkBack → Ativar
#    Anotar a versão em Configurações → Apps → TalkBack.
adb shell settings put secure enabled_accessibility_services \
  com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService
```

**Gestos do TalkBack usados no roteiro:** deslizar para a direita (próximo elemento) · para a
esquerda (anterior) · toque duplo (ativar) · deslizar para cima e depois direita (menu de leitura,
para alternar a navegação por *cabeçalhos*).

### 4.3 O caminho, parada a parada

Em cada parada está **o que se espera ouvir**. Divergência é achado — anote a fala real ao lado.

#### Tela 1 — Login

| # | Gesto | Esperado |
|---|---|---|
| 1 | abrir o app | foco em "Omni-Tribo", cabeçalho |
| 2 | → | "E-mail, caixa de edição" |
| 3 | toque duplo, digitar | eco do texto digitado |
| 4 | → | "Senha, caixa de edição" |
| 5 | → | "Entrar, botão" |
| 6 | toque duplo com senha errada | o erro é **falado sozinho**, sem precisar procurar (critério 12) |
| 7 | → | "Criar conta, link" — conferir que o alvo aceita o toque duplo (critério 9) |

#### Tela 2 — Radar / mapa

| # | Gesto | Esperado |
|---|---|---|
| 1 | entrar na aba Mapa | foco vai para o título da tela (critério 7) |
| 2 | → até o alternador | "Mapa, botão, selecionado" e "Lista, botão, não selecionado" |
| 3 | → sobre o mapa | **aqui é o achado esperado**: anotar tudo que o TalkBack fala dentro da WebView |
| 4 | menu de leitura → cabeçalhos | conferir se algum cabeçalho existe dentro do mapa |

#### Tela 3 — Radar / lista

| # | Gesto | Esperado |
|---|---|---|
| 1 | toque duplo em "Lista" | a apresentação troca |
| 2 | → | "Missões próximas, cabeçalho" |
| 3 | → | "Entrega, 69 XP e 23 tokens, a 180 m, termina em 3 h, …, botão" — **nesta ordem** |
| 4 | → até o fim das missões | "Pontos de custódia, cabeçalho" |
| 5 | → | "Ponto de custódia, loja, a 240 m, 47 de 50 vagas livres, …, botão" |
| 6 | fechar o app e reabrir | a lista continua escolhida (critério 6) |

#### Tela 4 — Detalhe + check-in

| # | Gesto | Esperado |
|---|---|---|
| 1 | toque duplo numa missão | foco no título da missão |
| 2 | → | categoria e status como texto, não só cor |
| 3 | → até a ação | "Fazer check-in, botão" + a dica sobre usar a localização |
| 4 | toque duplo **longe do local** | "Você ainda não chegou. Você está a N metros… " — falado sozinho, com a unidade **por extenso** |
| 5 | toque duplo **no local** | "Check-in confirmado…" com a recompensa — falado sozinho |

#### Tela 5 — Carteira

| # | Gesto | Esperado |
|---|---|---|
| 1 | entrar na aba | foco em "Carteira", cabeçalho |
| 2 | → | "N tokens" |
| 3 | → | "Transferir tokens, botão" |
| 4 | toque duplo | a folha abre; conferir se o foco entra nela |
| 5 | → | "@ do vizinho, caixa de edição" |
| 6 | digitar e ativar "Buscar vizinho" | o nome encontrado é **falado sozinho** |
| 7 | → até o fim da folha | **a folha rola e o botão de transferir é alcançável** (era o A6) |
| 8 | voltar ao extrato, → numa linha | "mais N tokens" / "menos N tokens" — a direção é falada (critério 2) |

---

## 5. NÃO CONFORME — lista de correção

Registrada, **não corrigida**: esta passada mede, e documento que conserta enquanto mede perde o
valor de medição. São **8 células**, agrupadas por causa — quatro no mapa e quatro de contraste
não-textual.

| # | Critério | Onde | Medição | Impacto |
|---|---|---|---|---|
| 1 | 1.3.1, 2.4.6, 4.1.2, 2.1.1 | Radar / mapa | WebView sem árvore de acessibilidade útil; `alt` descartado pelo Leaflet | **Alto por critério, mitigado por desenho** — a alternativa em lista existe e é equivalente (ADR 0030). Corrigir o mapa por dentro foi avaliado e recusado ali |
| 2 | 1.4.11 | Borda do chip não selecionado (radar, detalhe) | 1,30:1 contra piso 3,0 | Baixo — o texto interno a 7,47:1 já identifica o controle; a norma reprova mesmo assim |
| 3 | 1.4.11 | Trilho do `Switch` de consentimento (carteira/perfil) | 1,38:1 | Baixo — o estado é anunciado pelo componente nativo |
| 4 | 1.4.11 | Preenchimento da `BarraProgresso` | 2,46:1 | Baixo — o mesmo valor aparece em número ao lado, e a barra tem `accessibilityValue` |

Os itens 2 a 4 são o **A20** e o **A22** do inventário. A correção dos três é um commit no tema:
escurecer `linha` para uso como borda de controle, e trocar o preenchimento da barra para
`verdeEscuro`. **Não foi feita aqui de propósito.**

---

## 6. O que isto NÃO garante

- **A passada com TalkBack não foi executada.** Não havia `adb`, emulador nem aparelho na máquina
  onde este documento foi produzido. Tudo que está marcado CONFORME foi estabelecido por cálculo
  sobre os tokens de tema, leitura de estilos ou teste automatizado — **nada foi ouvido**. As seis
  células NÃO VERIFICADO são as que dependem exclusivamente disso; as CONFORME **também não foram
  confirmadas com leitor ligado**, apenas não dependem dele para serem estabelecidas.
- **Os prints a 200% não existem.** A conformidade com 1.4.4 é, hoje, uma inferência a partir dos
  estilos — nenhuma tela foi vista com a fonte no máximo.
- **iOS e VoiceOver não foram verificados, em nada.** Não há Mac nem iPhone no projeto, e o
  `CLAUDE.md` já registra essa certificação como fora de escopo. Diferenças conhecidas que importam:
  `accessibilityLiveRegion` **não existe no iOS** — lá o anúncio depende inteiramente de
  `announceForAccessibility` —, e `accessibilityViewIsModal` só tem efeito no iOS. Ou seja, os
  caminhos são **diferentes** nas duas plataformas, e só um deles tem sequer um roteiro escrito.
- **Um único leitor de tela, e de uma única versão.** Mesmo quando a passada for feita, ela dirá
  respeito ao TalkBack daquela versão. Leitores diferentes tratam `title`, `accessibilityHint` e
  ordem de leitura de forma diferente — e é justamente por isso que o campo "versão do TalkBack" é
  obrigatório no §4.1.
- **Nenhuma pessoa com deficiência participou.** Isto é a limitação mais séria da lista, e não é
  formalidade. Um desenvolvedor com visão testando com leitor de tela ligado verifica se a
  informação *existe*; não verifica se ela é *utilizável* por quem navega assim todo dia — ritmo,
  verbosidade, quais anúncios atrapalham em vez de ajudar, o que se aprende a ignorar. Conformidade
  com norma não é o mesmo que usabilidade, e nada aqui mede a segunda.
- **Não cobre todo o app.** Cinco apresentações de quatro telas. Ficam de fora: onboarding, cadastro,
  criação de missão, notificações, perfil, benefícios, resgate e o painel de impacto.
- **Não cobre navegação por teclado externo nem por switch access**, que são caminhos de entrada
  distintos do leitor de tela e têm modos de falha próprios.
- **Os vereditos de contraste são sobre os tokens, não sobre pixels renderizados.** Opacidade
  (`Botao` inativo a `opacity: 0.45`), sobreposição e o véu dos modais mudam os valores reais. O
  botão desabilitado, em particular, não foi medido.

---

## Referências

- `docs/auditoria/acessibilidade-inventario.md` — o levantamento estático que originou as F18 e F19
- `docs/adr/0030-radar-com-alternativa-em-lista.md` — por que o mapa não foi remendado
- `apps/mobile/eslint.config.js` — as 11 regras de acessibilidade que barram o build
- `apps/mobile/src/components/__tests__/acessibilidade.test.tsx` e
  `apps/mobile/app/__tests__/radarLista.test.tsx` — os testes citados na matriz
