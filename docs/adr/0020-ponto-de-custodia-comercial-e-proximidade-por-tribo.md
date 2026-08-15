# 0020 — Ponto de custódia comercial, e proximidade medida por tribo

**Data:** 2026-08-14
**Status:** Aceito

---

## Contexto

O módulo "Fim da Entrega Falida" é a tese do produto: uma entrega que falhou vira missão comunitária
remunerada. O brief do challenge descreve um mecanismo específico para ele (§7.3, "Regra de Raio de
Ação Restrito"):

> Para que uma "Missão de Recebimento" seja gerada durante uma entrega falida, é estritamente
> obrigatório que o sistema detecte membros presentes em um raio máximo de **50 metros** do endereço
> original de entrega.

E complementa que "o app detecta automaticamente quais membros certificados da Tribo estão
**presentes em casa**".

Duas coisas tornam isso inimplementável como escrito neste sistema.

**Primeira: "presente em casa" não é verificável pelo servidor.** A única fonte de posição de um
usuário seria a coordenada que o próprio aparelho reporta — exatamente o dado que
`docs/seguranca/antifraude-geolocalizacao.md` documenta como não confiável (spoofing com root ou
emulador é mitigável e não eliminável, e `mocked` é reportado pelo cliente). Um raio de 50 m sobre
dado auto-reportado não é um controle: é uma sugestão que qualquer um contorna, e sobre a qual a
custódia de um pacote de terceiro passaria a repousar.

**Segunda: não existe onde guardar essa posição.** A tabela `usuario` não tem coluna geográfica. As
únicas `GEOGRAPHY(POINT,4326)` do schema são `missao.origem`, `missao.destino`, `checkin.ponto` e
`ponto_custodia.ponto` — nenhuma descreve onde uma PESSOA está agora.

Ao mesmo tempo, o schema já traz desde a V6 uma entidade que resolve o problema por outro caminho:
`ponto_custodia`, com `capacidade`, `ocupacao` e `ativo`, e tipos `LOJA`, `LOCKER`, `PORTARIA`,
`VIZINHO`. O contexto do produto reforça: material de construção é volumoso, e a loja física do
bairro é ponto de custódia natural.

---

## Decisão

**Adotamos o ponto de custódia COMERCIAL como destino da encomenda, e não o vizinho em casa.** A
entrega falida é depositada num ponto com capacidade conhecida; a missão nasce ali, com
`origem = ponto de custódia` e `destino = endereço original de entrega`.

**Medimos proximidade por TRIBO, não por pessoa.** O alerta de missão nova alcança membros de tribos
com presença dentro de `app.notificacoes.raio-alerta-metros` (3 km) do ponto — via
`ConsultasGeoespaciais.tribosNoRaio`. Granularidade de bairro, não de indivíduo.

**"Perto" é a MENOR distância entre o alvo e as âncoras da tribo** (pontos de custódia ativos e
origens de missão), não a distância até o centro dela.

Isto último não foi a primeira escolha, e foi corrigido por um teste. A versão inicial usava o
centroide de `centroDaTribo`, e o seed já continha o contraexemplo: a **Tribo Pinheiros possui o
locker da Consolação**, ~3,8 km a leste, e o centroide resultante fica a mais de 3 km da própria loja
da tribo em Pinheiros. Uma encomenda no Leroy Merlin Pinheiros **não notificava ninguém de
Pinheiros** — e notificava a Vila Madalena. Bairro real é espalhado e às vezes côncavo; o centro
geométrico de uma região em U pode cair fora dela. `centroDaTribo` continua existindo, porque
responde a pergunta para a qual foi feito — onde centralizar um mapa.

---

## Consequências

**Positivas:**

- A custódia passa a repousar em quem tem horário de funcionamento, funcionário e responsabilidade
  civil, em vez de na presença não verificável de um vizinho.
- Capacidade vira uma restrição REAL e checável: `ocupacao >= capacidade` recusa a encomenda, e a
  recusa é gravada. O modelo do brief não tinha como expressar "não cabe mais".
- Nenhum dado novo de localização pessoal é armazenado. Num projeto que já tem exportação LGPD e
  anonimização, não criar a coluna é a decisão mais barata de defender.
- O raio de 3 km casa com a escala do produto ("missões hiperlocais no bairro") e com o seed.

**Negativas / trade-offs:**

- **Divergimos do brief num ponto explícito**, e isso precisa ser dito na banca em vez de escondido.
  O que entregamos não é o mecanismo dos 50 m; é outro mecanismo para o mesmo objetivo.
- A notificação é menos precisa: alguém na borda da tribo pode ser avisado de algo a 4 km, e alguém
  de tribo vizinha a 200 m pode não ser, se a tribo dele não tiver âncora perto.
- Depende de haver ponto de custódia cadastrado no bairro. Sem ponto, não há missão — enquanto o
  modelo do vizinho, em tese, funcionaria em qualquer rua.
- A distância devolvida por `tribosNoRaio` é a da âncora mais próxima, então uma tribo pode aparecer
  "a 200 m" com a maior parte dos membros longe.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Vizinho em casa num raio de 50 m, como no brief | Exige posição confiável e contínua do usuário. O servidor não tem como verificar presença, e a única fonte seria a coordenada auto-reportada que o próprio documento de antifraude classifica como não confiável. Fazer a custódia de pacote de terceiro depender dela seria construir sobre o controle mais fraco do sistema. |
| Derivar a posição do usuário do último check-in válido | Cobre só quem já executou missão; devolve posição HISTÓRICA, não atual; e reaproveita a trilha antifraude do `checkin` para segmentação, o que é desvio de finalidade sob a LGPD — o dado foi coletado para provar presença numa missão, não para decidir quem recebe anúncio. |
| Coluna `usuario.ultima_posicao` alimentada pelo app | Cria armazenamento de localização precisa e contínua de pessoa física, com decisões de retenção, minimização e base legal que o projeto não tem. Custo de conformidade desproporcional ao ganho de precisão numa notificação de bairro. |
| Distância até o CENTROIDE da tribo | Foi implementada e reprovada por teste com dado do próprio seed: a Tribo Pinheiros tem centroide a mais de 3 km da sua própria loja, por causa do locker da Consolação. Centroide responde "onde é o meio", não "esta tribo alcança este lugar". |
| Notificar apenas a tribo DONA do ponto de custódia | Simples e sem geoespacial, mas cega para o vizinho de tribo adjacente que está a duas quadras — que é exatamente o público que a tese do produto quer alcançar. |
