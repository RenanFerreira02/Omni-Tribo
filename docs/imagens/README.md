# Imagens do README

Os cinco arquivos abaixo **ainda não existem** — o `README.md` já os referencia, então ele mostra
imagem quebrada até que sejam colados aqui. É de propósito: um marcador em comentário HTML passa
despercebido num commit, um retângulo cinza no topo da página não passa.

| Arquivo | O que mostra | Como gerar |
|---|---|---|
| `demo.gif` | O ciclo da tese em ~20 s: radar → aceitar → check-in → carteira creditada | Grave a tela do emulador ou do celular e converta (abaixo) |
| `radar.png` | A aba de missões com o radar geoespacial, distâncias visíveis | Print da tela `(tabs)/mapa` ou `(tabs)/index` |
| `missao-checkin.png` | O detalhe da missão com o botão de check-in e o aviso de risco | Print de `(app)/missao/[id]` |
| `carteira.png` | Saldo em TOKEN e o extrato de lançamentos | Print de `(tabs)/carteira` |
| `beneficios.png` | O catálogo de benefícios do bairro, com o custo em tokens | Print de `(app)/beneficios` |

## Dimensões

- **GIF:** 800 px de largura, ~20 s, **sem áudio**, laço infinito. Acima de ~10 MB o GitHub demora a
  carregar no README — é o limite prático a respeitar.
- **PNG:** largura do próprio aparelho (1080–1440 px). O README os exibe numa tabela 2×2, então cada
  um aparece com cerca de metade da largura da página.

## Como gerar

```bash
# grave a tela do emulador (Android)
adb shell screenrecord --time-limit 20 /sdcard/demo.mp4
adb pull /sdcard/demo.mp4

# converta para GIF em 800px, 12 fps, com paleta própria (bem menor que o -f gif direto)
ffmpeg -i demo.mp4 -vf "fps=12,scale=800:-1:flags=lanczos,palettegen" -y /tmp/pal.png
ffmpeg -i demo.mp4 -i /tmp/pal.png -lavfi "fps=12,scale=800:-1:flags=lanczos[x];[x][1:v]paletteuse" \
       -y docs/imagens/demo.gif

# print
adb exec-out screencap -p > docs/imagens/radar.png
```

No celular físico, qualquer gravador de tela serve — o `ffmpeg` acima converte igual.

## Antes de gravar

Rode o preparo do [`ROTEIRO-DEMO.md`](../ROTEIRO-DEMO.md): `make reset` e o backend de pé, com o
seed reconstruído. Gravar sobre banco sujo de ensaio põe missão de teste e saldo estranho na imagem
que abre o repositório.
