# Prompt para gerar o deck no Claude Design

Cole o bloco inteiro abaixo. Ele é autossuficiente de propósito: **todo o conteúdo dos dez slides
está embutido**, porque um prompt que só aponta "use o deck do repositório" faz o modelo preencher
lacunas com número inventado — e metade deste projeto é justamente não afirmar o que ninguém mediu.

A fonte de verdade do conteúdo continua sendo [`DECK-BANCA.md`](DECK-BANCA.md). Se você editar lá,
edite aqui também, ou o deck gerado envelhece em silêncio.

---

```
/design

Crie um canvas com 10 artboards de 1920×1080 (16:9): os slides de uma apresentação acadêmica que
acompanha um vídeo-pitch de 5 minutos. Tudo em português do Brasil.

## O produto

Omni-Tribo — app de missões sociais hiperlocais. A tese, em uma frase: uma entrega que falhou vira
missão comunitária remunerada. A transportadora reporta a falha, a encomenda fica num ponto de
custódia do bairro, nasce uma missão de retirada, um vizinho aceita, faz check-in geolocalizado e
leva ao destinatário; a transportadora confirma e ele é creditado em token comunitário, resgatável
em benefícios de parceiros do bairro. Projeto acadêmico FIAP, Enterprise Challenge Leroy Merlin.

## Regras duras

- NÃO invente número, métrica, logo, depoimento, print de tela ou nome de cliente. Use apenas o que
  está escrito abaixo. Onde não houver dado, não preencha com dado — use espaço em branco.
- Mantenha os dois marcadores literais, visíveis: <COLAR-LINK-YOUTUBE> e <NOME COMPLETO>.
- O slide 1 PRECISA trazer o link do vídeo no YouTube e o link do repositório. É exigência literal
  do enunciado da atividade — não mova para o fim, não esconda em rodapé pequeno.
- Máximo 4 bullets por slide. O texto abaixo já está no limite: não expanda, não acrescente bullets
  "para preencher".
- Nada de notas do apresentador nos slides. Elas existem, mas ficam fora da arte.
- Não use logotipo da FIAP nem da Leroy Merlin: eu não tenho licença dos ativos. Cite os nomes como
  texto, que é o que o enunciado pede.

## Identidade visual

Use a paleta REAL do aplicativo — ela foi auditada para contraste WCAG AA e os hex são fixos:

  verdePrimario #1D9E75   verdeEscuro #0F6E56   verdeClaro #E1F5EE
  ambar #BA7517           coral #D85A30         papel #F7F9F8
  tinta #1A2520 (texto)   tinta70 #4A5853       linha #D7DDDA (bordas)

Para TEXTO em tom menor use as variantes acessíveis, não as cores de preenchimento:
  texto suave #6A7571 · âmbar de texto #9C6213 · coral de texto #AF4927

Fundo padrão dos slides: papel #F7F9F8, com tinta #1A2520 no texto. Verde é a cor de ação e de
destaque; âmbar e coral entram só como acento pontual (risco, alerta), nunca como fundo de slide
inteiro. Um slide pode inverter para verdeEscuro com texto branco se isso marcar uma virada — use
no máximo dois assim, para que a inversão signifique alguma coisa.

Tipografia: família do sistema (sem fonte comprada). Escala do app, ampliada para projeção —
título de slide ~64px, subtítulo ~34px, bullet ~28px, legenda ~20px. Peso 700 em título, 400 em
corpo. Hierarquia por tamanho e peso, não por cor.

Tom: bairro, vizinhança, concreto. Nada de gradiente roxo de SaaS, nada de foto de banco de imagens,
nada de ícone 3D. Se usar ilustração, que seja geometria simples na própria paleta. Espaço em branco
generoso: cada slide é visto por 15 a 30 segundos, de longe.

## Os dez slides

SLIDE 1 — Capa
Título: Omni-Tribo — uma entrega que falhou vira missão comunitária remunerada
- 🎥 Vídeo-pitch: <COLAR-LINK-YOUTUBE>
- 💻 Repositório: https://github.com/RenanFerreira02/Omni-Tribo
- <NOME COMPLETO> — RM 555833 — Sistemas de Informação, FIAP
- Enterprise Challenge · Leroy Merlin — SMART HAS & AI Logistics Extension
Os dois links precisam ser legíveis à distância. Este é o slide mais importante do deck.

SLIDE 2 — O problema
Título: Entrega falida é caro duas vezes
- O entregador não encontra ninguém; o pacote volta ao centro de distribuição
- O varejista paga re-entrega, armazenagem e o risco de perder o cliente
- A segunda tentativa repete as mesmas condições que fizeram a primeira falhar
- Do outro lado da mesma rua, tem gente que passaria em frente à loja de qualquer jeito

SLIDE 3 — A tese, e o ciclo
Título: O custo do fracasso vira renda de vizinho
- A encomenda fica num ponto de custódia do bairro — locker, portaria, comércio parceiro
- A entrega frustrada nasce como missão de retirada aberta, com a recompensa já financiada
- O vizinho aceita, faz check-in geolocalizado e leva ao destinatário
- A transportadora confirma o recebimento; só então o executor é creditado
Este slide pede um diagrama de ciclo com cinco passos, na paleta. Deixe claro que a confirmação
vem da transportadora, não do vizinho.

SLIDE 4 — AI Logistics Extension
Título: Prever se a próxima tentativa também vai falhar
- Entra no instante em que a transportadora reporta a falha, antes de a missão nascer
- 14 sinais do caso: tentativas anteriores, janela de horário, tipo de endereço, histórico do CEP,
  peso, volume e clima consultado ao vivo
- Sai em três coisas: quanto a missão paga, a prioridade do alerta e o aviso acionável na tela
- Modelo interpretável: cada previsão diz quais fatores mais pesaram nela

SLIDE 5 — Como a previsão vira dinheiro
Título: Missão mais difícil paga mais — dentro de um teto
- multiplicador = 1,00 + 0,50 × probabilidade, sempre em [1,00; 1,50]
- Entra na base da recompensa, junto da complexidade — nunca sobre o total
- Piso 1,00: risco nunca reduz recompensa, o que inverteria a tese
- Fica congelado na missão, junto da versão da fórmula
A fórmula merece destaque tipográfico; é o slide mais técnico que o deck tem.

SLIDE 6 — A economia
Título: Três moedas, e uma invariante que é medida
- XP reputação, não transferível · TOKEN moeda do bairro · BRL fora do ciclo de missões
- Quem cria a missão não paga: a recompensa é calculada pelo servidor e congelada na criação
- O token entra por aporte do patrocinador e sai de circulação no resgate de um benefício
- Publicar exige pote cobrindo a recompensa; cancelar ou expirar estorna a quem financiou
- Missão comunitária pode valer **só XP**: aí não há pote, publica na hora e token nenhum se move

SLIDE 7 — Arquitetura
Título: Monólito modular, com a fronteira verificada por teste
- Spring Boot 4.1 · Java 21 · PostgreSQL + PostGIS · Flyway · app Expo / React Native
- Oito módulos; um só fala com outro por porta pública ou evento, e o ArchUnit reprova o resto
- Proximidade é resolvida no banco pelo PostGIS a cada consulta — distância nunca é armazenada
- Operação de valor sob lock pessimista, ledger só-inclusão: correção é estorno, nunca UPDATE

SLIDE 8 — O que foi medido
Título: Número que ninguém mediu não entra
- Carga local, três cenários: 14.967 requisições, zero 5xx, zero deadlock; o radar não degrada
  até 74,6 req/s
- EXPLAIN ANALYZE real provando uso do índice GiST — saída do planejador, não afirmação
- Carteira sob 100 threads, com deadlock cruzado e rollback verificados
- Teste de mutação nos dois domínios que guardam dinheiro e máquina de estados, sem gate
Estes quatro números são medidos e estão em arquivo versionado. Não arredonde, não embeleze,
não acrescente nenhum outro.

SLIDE 9 — Escopo declarado
Título: O que ficou fora, e por quê
- Dados do modelo de risco são sintéticos — 5.000 entregas geradas, correlações injetadas por nós.
  Validar com dado real é o próximo passo
- A última emissão de token é a entrega criada por um vizinho no app: emite porque ali não existe
  transportadora a debitar, e cobrar da tribo inverteria o modelo
- Fora do MVP por decisão registrada: pagamento real e KYC, push remoto, cotação token→real
- Quatro instrumentos de diagnóstico são consulta ativa: mostram o problema, não avisam
Este slide é ativo, não defensivo — desenhe-o com a mesma dignidade dos outros, sem tom de
errata e sem ícone de alerta vermelho.

SLIDE 10 — Equipe e entrega
Título: <NOME COMPLETO> — RM 555833
- (reserve um espaço circular para a foto do integrante, com moldura na paleta)
- Sistemas de Informação — FIAP
- FIAP NEXT, 24/10: sim, desejo expor o projeto.
- 🎥 <COLAR-LINK-YOUTUBE> · 💻 https://github.com/RenanFerreira02/Omni-Tribo
A resposta sobre o FIAP NEXT precisa estar visível, não em letra miúda: é exigência do enunciado.

## Checagem antes de entregar

- Os dez artboards existem, na ordem, em 1920×1080.
- <COLAR-LINK-YOUTUBE> e <NOME COMPLETO> aparecem literalmente, nos slides 1 e 10.
- Nenhum número aparece no deck que não esteja no texto acima.
- Nenhum slide tem mais de 4 bullets.
- Texto sobre fundo com contraste suficiente para projeção: nada de cinza-claro sobre papel.
```

---

## Depois de gerar

O canvas sai editável — ajuste à mão o que não ficar bom, em vez de reescrever o prompt para uma
quinta tentativa. E confira os dois marcadores: se o modelo os tiver "resolvido" inventando um link
ou um nome, é o único erro deste deck que passa despercebido numa revisão rápida e desmonta na
banca.
