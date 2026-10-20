# FIAP NEXT — o estande

Sábado, 24/10, Espaço Arca (Av. Manuel Bandeira, 360 — Vila Leopoldina). G15 com Fedora, celular
Android com Expo Go e internet pelos dados móveis do celular.

## Véspera (em casa, com calma)

1. `git pull` na `main` e, em `apps/mobile`, `npm install`.
2. Crie `apps/mobile/.env` (fora do git) com a chave do mapa:
   `EXPO_PUBLIC_GOOGLE_MAPS_KEY=...`. Sem ela o mapa usa o Leaflet: funciona, mas não é o Google.
3. **Ensaio completo**, exatamente como no estande (abaixo), com o celular no hotspot.
4. No celular: Expo Go instalado; entre como `renan@omnitribo.dev` (`Senha@123`) e **deixe
   logado**. O bloqueio de login após 5 erros não tem como ser desfeito na hora.
5. Carregue o celular e o G15. O hotspot gasta bateria.

## No estande (15 min antes de abrir)

```bash
# 1. Rede: ligue o hotspot do celular e conecte o G15 nele.
sudo firewall-cmd --add-port=8080/tcp --add-port=8081/tcp

# 2. Banco do zero (recusa se a API já estiver de pé — pare-a antes).
make feira

# 3. Três terminais:
cd services/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,feira   # terminal 1
cd apps/mobile && npm start                                                     # terminal 2
bash tools/feira/povoar.sh && bash tools/feira/entrega.sh                       # terminal 3

# 4. Com o CELULAR junto ao PC, leia a posição dele (Google Maps → toque no ponto azul) e:
bash tools/demo/ponto-aqui.sh <LAT> <LON>

# 5. Confira tudo:
bash tools/demo/checar-ambiente.sh      # espere todos os ✓ — ele imprime o IP do hotspot
```

No celular, leia o QR do terminal 2 com o Expo Go.

**Telão:** no navegador do G15, abra `http://localhost:8081`, entre como `admin@omnitribo.dev` e vá
para `http://localhost:8081/impacto?telao=1`. A tela atualiza sozinha a cada 5 s. **Não recarregue a
página**: na web a sessão vive só na aba, e recarregar desloga (decisão de segurança, 0013).

## Roteiro por visitante (~2 min)

| Tempo | No celular | O que dizer |
|---|---|---|
| 0:00 | — | "Quando o entregador não encontra ninguém, o pacote volta e a loja paga de novo. Aqui, ele vira trabalho para um vizinho." |
| 0:15 | **terminal 3: Enter** | "Acabou de falhar uma entrega aqui no estande." |
| 0:25 | aba **Avisos** (puxe para atualizar) | "Quem mora perto e aceitou ser avisado recebe." |
| 0:35 | aba **Missões** → radar no mapa | "A distância é calculada no banco, a cada consulta. Em volta, o bairro: entregas da Leroy Merlin Villa Lobos, mutirão no parque, coleta de recicláveis." |
| 0:50 | toque na missão → **detalhe** | "Esta é uma entrega difícil: o modelo de IA estimou que a próxima tentativa também pode falhar, e a missão paga mais por isso, dentro de um teto. E diz o que fazer: combine o horário antes de ir." |
| 1:10 | **Aceitar → Iniciar → Check-in** | "O GPS do celular é validado no servidor. Presença não é recebimento: quem confirma é a transportadora." |
| 1:25 | terminal 3 mostra **+N tokens** | "A transportadora confirmou." |
| 1:30 | aba **Carteira** (puxe para atualizar) | "Crédito com extrato que só aceita inclusão: correção é estorno, nunca apagar linha." |
| 1:45 | **Resgatar benefícios** → padaria | "O token vale coisa de verdade no bairro e, ao ser trocado, sai de circulação." |
| 1:55 | aponte o **telão** | "E o painel mostra o impacto: entregas que viraram missão e custo de reentrega evitado." |

Para o próximo visitante, basta Enter de novo no terminal 3. Cada rodada cria uma missão nova.

## Plano B

| Sintoma | O que fazer |
|---|---|
| Mapa com outro visual (Leaflet) | A chave do Google falhou ou caiu a rede; o app troca sozinho. Siga: os pinos e distâncias vêm do banco. |
| Mapa cinza / sem rede | Use **Lista** no topo do radar: mesma informação, sem tiles. |
| Check-in recusado por precisão | GPS fraco dentro do pavilhão. Aproxime-se de uma porta ou janela e tente de novo (o limite do profile `feira` é 150 m). |
| Check-in recusado por distância | O ponto do estande não está onde o celular está: refaça o passo 4 (`ponto-aqui.sh`). |
| Celular não abre o app / login gira | Celular e PC em redes diferentes, ou firewall. `checar-ambiente.sh` diz qual. Força o endereço: `EXPO_PUBLIC_API_URL=http://<ip>:8080 npm start`. |
| `entrega.sh` diz `RECUSADA` ou `SEM_PATROCINIO` | Ponto lotado ou patrocinador sem saldo (ensaio demais): pare a API, `make feira`, suba de novo. |
| Aviso não chega | Espere ~10 s (a outbox despacha em lote) e puxe a aba para atualizar. |
| A API não sobe: `BindException` | Porta ocupada: `ss -lptn 'sport = :8080'` e encerre o processo. |
| Telão desligou | Recarregou a página: entre de novo como admin. |

## Perguntas prováveis

- **"Os dados do modelo são reais?"** Não, são sintéticos, e isso é declarado. Validar com
  operação real é o próximo passo. Ver [MODELO-RISCO.md](MODELO-RISCO.md).
- **"O token vira dinheiro?"** Não. Token convertível é dinheiro, com KYC e regulação junto. Ele
  vale benefícios do bairro e é queimado no resgate. Ver [ECONOMIA.md](ECONOMIA.md).
- **"E se alguém falsificar o GPS?"** O servidor recusa localização simulada e precisão ruim, mas
  spoofing com root é mitigável, não eliminável. Por isso quem confirma é a transportadora. Ver
  [ARQUITETURA.md](ARQUITETURA.md).
