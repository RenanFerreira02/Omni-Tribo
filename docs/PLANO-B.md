# Plano B — folha de consulta rápida

Uma linha por bloco, para ler **em pé, com a banca olhando**. O raciocínio de cada plano B está no
bloco correspondente de [`ROTEIRO-DEMO.md`](ROTEIRO-DEMO.md) — este arquivo é o índice, não a fonte.
Se os dois divergirem, o roteiro vence.

> **Antes de qualquer diagnóstico:** `bash tools/demo/checar-ambiente.sh`. Ele responde em sete
> linhas onde está o problema, e imprime o IP da máquina. Quase todo caso abaixo aparece lá como ✗
> com a ação corretiva ao lado.

---

## Por bloco

| # | Bloco | Se falhar |
|---|---|---|
| 1 | 0:00 · O problema | Nada a falhar — é fala, sem tela. É o bloco para onde recuar enquanto alguém conserta outro. |
| 2 | 1:00 · O aporte | O `curl` é local: se falhou, o backend caiu. Se voltar `"replay": true`, **não é erro** — é a idempotência; mostre e siga. |
| 3 | 2:00 · Entrega falida vira missão | Check-in reprovado por distância = as quatro coordenadas não batem com o ponto escolhido (raio de 200 m). `SEM_PATROCINIO` = saldo do patrocinador acabou no ensaio → `make demo` de novo. **Crédito diferente do que está no roteiro não é defeito** — o multiplicador de risco é congelado por missão e o modelo consome clima ao vivo; leia o `+N` impresso. |
| 4 | 4:00 · O resgate | Saldo insuficiente → resgate o de 10 tokens (remendo de câmara). Catálogo vazio → `triboId` errado; é o da Tribo Cidade Líder. |
| 5 | 5:00 · Resiliência | **O plano B é melhor que o plano A.** Desligue o Wi-Fi de propósito e mostre o 503 com `type` estável. Ensaie este. |
| 6 | 6:00 · A economia | Script falhou → os mesmos números, já executados, em [`evidencias/f14-conservacao-quatro-categorias.md`](evidencias/f14-conservacao-quatro-categorias.md). |
| 7 | 8:00 · Painel de impacto | **Corte.** É o único bloco opcional do roteiro. |
| 8 | 9:00 · Qualidade | Sem projetor: são todos Markdown e leem no GitHub pelo celular. |

---

## Os casos que não são de bloco nenhum

### "A internet caiu"

Duas consequências **diferentes**, e é importante não confundi-las na resposta:

- **Bloco 5 (ViaCEP/Open-Meteo):** vira demonstração. Está no roteiro.
- **O mapa fica cinza.** `MapaLeaflet.tsx` desenha com Leaflet e **tiles do OpenStreetMap dentro de
  uma WebView** (ADR 0012) — o tile vem da rede, sempre. O que **continua certo** é tudo o que
  importa: os marcadores, as distâncias e a ordenação do radar vêm do PostGIS local, não do tile.
  Diga isso e conduza pela **lista** de missões, que mostra a mesma distância sem depender de rede.
- Tudo o mais é `localhost`. Blocos 2, 3, 4, 6 e 7 não tocam a internet.

### "O celular não conecta"

Nesta ordem, sem pular etapa:

1. `bash tools/demo/checar-ambiente.sh` — a linha da 8080 já separa "backend fora do ar" de
   "firewall". Ela testa pelo **IP da LAN**, não por `localhost`, que é o que o celular enxerga.
2. Firewall: `sudo firewall-cmd --add-port=8080/tcp`.
3. Force o endereço: `EXPO_PUBLIC_API_URL=http://<ip>:8080 npm start` — o IP sai do script.
4. Celular e PC na **mesma rede**. Rede de convidados costuma isolar clientes entre si; nesse caso
   nada acima resolve — use o roteador do celular, ou o item 5.
5. Último recurso: **Expo Web** no navegador da própria máquina, contra `localhost`. Perde o GPS
   real, então o radar precisa de coordenada digitada.

> `localhost` dentro do celular é o **próprio celular**. É o erro nº 1, e o sintoma é o botão
> `Entrar` girando até "não foi possível falar com o servidor".

### "`make demo` falha no banco"

O CLI do `docker` sobrevive ao Docker Desktop desinstalado e continua apontando para um socket que
não existe mais, enquanto o banco roda sob **podman**. `tools/demo/compose.sh` detecta isso sozinho
e avisa na tela. Se nem ele achar runtime:

```bash
systemctl --user start podman.socket
```

### "O backend não sobe: `BindException: Endereço já em uso`"

O Spring aborta a aplicação **inteira** e o stack trace não menciona quem tomou a porta.

- **8090** é a porta de gestão, movida para lá justamente por causa do Metro. Não a mova de volta.
- **8080** ocupada é quase sempre um `spring-boot:run` esquecido de um ensaio:
  `ss -lptn 'sport = :8080'` e mate o PID.

### "O login não responde, mas o `ping` responde"

O backend está ligado a um **banco sem schema**: alguém rodou `make demo` (ou `make reset`) com ele
de pé, e o Flyway só migra no boot. `bash tools/demo/checar-ambiente.sh` diz isso na linha do
schema. A saída é **reiniciar o backend** — não mexer no banco.

O `make demo` recusa quando acha algo na 8080 justamente para não criar este estado.

### "Errei a senha e o login travou"

Bloqueio progressivo, 5 tentativas por minuto. **Não há como destravar na hora** — a chave é
`sha256(ip+email)`. Por isso o roteiro manda entrar na sala com o app **já logado**: use-o, e não
faça login ao vivo sem necessidade.

### "Ensaiei agora e os números estão estranhos"

`make demo`. O ensaio gasta o saldo do patrocinador, ocupa vagas do ponto de custódia e queima token
no resgate — e o bloco 7 fica com `resgatados` diferente de zero antes de você resgatar ao vivo,
que é justamente o efeito que aquele bloco existe para mostrar.
