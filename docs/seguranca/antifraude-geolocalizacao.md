# Antifraude na geolocalização — o que pega, o que não pega

**Fase:** F6 · **Data:** 2026-08-07

Este documento existe para ser lido por quem vai avaliar o sistema, inclusive de forma adversarial.
A conclusão principal está aqui em cima, sem rodeios:

> **Falsificação de GPS por aparelho com root ou emulador é mitigável, não eliminável.** Nenhum
> controle deste conjunto impede um cliente comprometido de reportar a coordenada que quiser. O que
> os controles fazem é elevar o custo do ataque e garantir que toda tentativa deixe rastro.
> A defesa que de fato fecha o caso não é técnica: é a confirmação humana do criador, na F7.

Quem afirmar que um app de missões geolocalizadas é "à prova de spoofing" está errado ou vendendo
alguma coisa. O que dá para fazer bem é o que está descrito abaixo.

---

## 1. O que estas medidas realmente pegam

Cada controle abaixo tem um atacante concreto em mente. Todos rodam **no servidor**; nenhum aceita
número calculado pelo cliente.

| Controle | Onde | Ataque que interrompe |
|---|---|---|
| `ST_Distance` contra `missao.raio_checkin_m` | `ConsultasGeoespaciais` | Check-in de casa. O cliente informa onde diz estar; a régua é do servidor. Distância vinda do cliente é ignorada — não existe campo para ela no DTO. |
| `acuraciaM > 50` rejeita | `AvaliacaoAntifraude` | Fix ruim passando por presença. Com raio de erro de 50 m sobre alvo de 50 m, "dentro" e "fora" são indistinguíveis — afirmar presença aí seria chute. |
| `mocked == true` rejeita | `AvaliacaoAntifraude` | App de mock location de prateleira, que o Android expõe via `isFromMockProvider`. Pega o atacante casual, que é a maioria. |
| `Idempotency-Key` obrigatória, UNIQUE no banco | V12 + `RegistroCheckinService` | Replay de requisição capturada. Reenviar o mesmo pacote não gera segundo check-in nem segunda transição. |
| Velocidade implícita > 120 km/h marca `suspeito` | `AvaliacaoAntifraude` | Teleporte entre check-ins sucessivos: mesma conta em dois pontos incompatíveis com deslocamento físico. |
| `checkin` append-only (`REVOKE UPDATE, DELETE`) | V4 | Apagar as próprias pegadas. Nem a aplicação consegue — só `INSERT` e `SELECT` são concedidos a `omnitribo_app`. |
| 403 antes de 409, e ambos antes de qualquer escrita | `MissaoService.registrarCheckin` | Sondar o estado de missão alheia pela diferença entre respostas de erro. |

Detalhe que sustenta o resto: **toda tentativa é gravada, inclusive as rejeitadas**, com
`valido = false` e `motivo_rejeicao`. A trilha não é subproduto do sucesso.

Isso é mais difícil de implementar do que parece, e a primeira versão errou. A rejeição precisa
COMMITAR a linha de auditoria e FALHAR a requisição com 422 — coisas que uma transação não faz junto.
A saída óbvia é gravar numa transação separada (`REQUIRES_NEW`), e foi o que se fez primeiro; o
defeito é que a transação externa segura `SELECT ... FOR UPDATE` sobre a missão enquanto a interna
pede uma **segunda conexão**. Com N requisições simultâneas e pool de tamanho P, bastava N ≥ P para
todas as conexões ficarem presas esperando conexões que nunca viriam — deadlock de pool, e a
aplicação inteira parava, inclusive o login. `CheckinConcorrenteTest` reproduziu com 50 threads.

A solução foi mover **onde o 422 é lançado**, não onde a linha é gravada: o serviço devolve o
veredito como valor, a transação (uma só, uma conexão só) commita nos dois casos, e o controller
lança o 422 depois do commit. De quebra, o caminho aceito ficou atômico — linha de check-in,
transição e trilha no mesmo commit.

Duas coisas que **não** geram linha, de propósito: quem não é o executor (403) e missão fora de
`EM_ANDAMENTO` (409). Não são tentativas de check-in que falharam na validação geoespacial; são
chamadas sem contexto de execução, e poluiriam a trilha com ruído.

---

## 2. O que estas medidas NÃO pegam — e não têm como pegar

### 2.1 Root, emulador, ou cliente reimplementado

`mocked` é uma flag **reportada pelo cliente**. Um aparelho com root injetando coordenadas na camada
do sistema operacional não aciona `isFromMockProvider`: para o app, a posição falsa é a posição real.
E um cliente reimplementado — alguém que fale HTTP direto com a API — simplesmente não envia a flag,
ou envia `false`.

O campo é `Boolean` e ausente equivale a `false`. Isso é uma decisão consciente e vale registrar por
escrito: **um atacante que omite `mocked` é indistinguível de um usuário honesto cujo aparelho
reportou `false`.** A flag pega o atacante casual e nada além disso. Tratá-la como defesa séria seria
confundir "o cliente colaborou" com "o servidor verificou".

Atestação de integridade de dispositivo (Play Integrity, App Attest) é o próximo degrau real, e está
fora do escopo do MVP local.

### 2.2 Presença não é execução

A geolocalização prova, no melhor caso, que **um aparelho** esteve perto de uma coordenada. Não prova
que a pessoa certa estava com ele, nem que a entrega foi feita, nem que o lixo foi recolhido. Alguém
pode caminhar até o ponto, fazer check-in e ir embora sem executar nada. Nenhum controle deste
documento toca nesse caso — é o que a confirmação humana da F7 existe para resolver.

### 2.3 Conluio entre criador e executor

Se as duas partes combinam, todos os controles técnicos são satisfeitos honestamente: o executor está
no lugar certo, o GPS é real, a confirmação humana vem. Detectar isso exigiria análise de padrão
entre contas (grafo de pares que só transacionam entre si, frequência anômala), que não existe e não
está planejada. Registrado como limitação conhecida, não como problema resolvido.

### 2.4 A cinemática é cega no primeiro check-in

A plausibilidade compara com o check-in **anterior do mesmo usuário**. Uma conta nova não tem
anterior: a primeira falsificação de cada conta passa sem qualquer sinal cinemático. Um atacante que
cria uma conta por fraude nunca dispara essa regra. Ela encarece a fraude repetida, não a primeira.

Decisão relacionada: a comparação usa o último check-in **independentemente de ter sido aceito ou
rejeitado**. Filtrar por `valido = true` permitiria lavar a trilha — teleportar, ser rejeitado (linha
não contaria), voltar, e o próximo check-in não teria contra o que ser medido.

### 2.5 Raio estrito é decisão, não descuido

A distância é comparada com `raio_checkin_m` **sem tolerância pela acurácia informada**. Somar a
acurácia ao raio pareceria generoso com o usuário de sinal ruim, mas entregaria ao atacante um
parâmetro para alargar o próprio alvo: bastaria declarar um fix ruim para ganhar dezenas de metros.
O corte em 50 m de acurácia já protege o caso legítimo — a orientação é tentar de novo a céu aberto.

---

### 2.6 O radar expõe a localização das missões abertas — de propósito

`GET /missoes/proximas` devolve a missão inteira, incluindo `origem`, `destino`, `cep` e
`logradouro`, para qualquer conta autenticada. Não é descuido: é o produto. Um radar de missões
hiperlocais existe para responder "o que dá para fazer perto de mim", e uma missão que não revela
onde é não pode ser avaliada nem aceita. Publicar uma missão é, por construção, uma oferta pública.

O resíduo, dito na íntegra: como o registro é aberto, uma conta nova pode varrer uma grade de
coordenadas e colher os endereços de todas as missões ENTREGA abertas de uma região. Endereço
residencial é dado pessoal, e essas missões ainda não têm executor definido.

Três coisas limitam o dano hoje, e nenhuma delas o elimina: o rate limit de leitura por usuário; o
raio máximo de 20 km por consulta; e o fato de que só missões `ABERTA` entram — rascunho não aparece
nem para o próprio criador, e missão aceita sai do radar.

Se o projeto seguir para produção com usuários reais, o caminho é reduzir a projeção do radar
(origem arredondada, bairro em vez de logradouro, `destino` só depois do aceite e só para criador e
executor) — não desligar o radar. Fica registrado como decisão de escopo do MVP acadêmico, tomada
com o custo à vista, e não como algo que passou despercebido.

## 3. Falsos positivos que assumimos

Honestidade também vale para o custo do controle sobre quem não está atacando.

- **120 km/h dispara em situação legítima e comum:** rodovia, trem, metrô entre estações distantes,
  e qualquer check-in feito pouco depois de um voo. A taxa de falso positivo aqui não é desprezível.
  É exatamente por isso que velocidade implausível **marca** (`suspeito = true`) e **não rejeita**: o
  check-in transiciona a missão normalmente e a suspeita vira insumo de revisão, não veredito.
- **50 m de acurácia estoura com frequência em cânion urbano**, entre prédios altos, e em ambiente
  interno. O usuário legítimo é recusado e precisa repetir. É um custo real, aceito por ser
  preferível a validar presença com uma medida que não sustenta a afirmação.
- **A saturação da velocidade é intencional.** `velocidade_implicita_kmh` é `NUMERIC(10,2)`, e um
  deslocamento intercontinental entre duas requisições HTTP produz valor acima do que a coluna
  comporta. O valor satura em 99.999.999,99 em vez de estourar. É sinal, não medida: acima do limiar,
  a decisão é a mesma.

### O que o cliente vê, e o que não vê

A mensagem de rejeição **informa a distância medida** ("Você está a 340 m da origem; o raio permitido
é 50 m"). O atacante já conhece esse número — ele escolheu a coordenada —, e sem ele o usuário
legítimo não consegue distinguir "ando cinco metros" de "estou no bairro errado".

Já a marca `suspeito` **não é exposta em resposta nenhuma**. Contar ao fraudador que ele foi
sinalizado ensina exatamente quanto desacelerar na próxima tentativa. A resposta de um check-in
suspeito é idêntica à de um check-in limpo.

---

## 4. Defesa em profundidade

A validação geoespacial é a **primeira** camada, e sozinha ela não decide nada de valor.

1. **Servidor mede tudo.** Distância, acurácia e plausibilidade são calculadas a partir do que o
   cliente informa, nunca aceitas prontas. Não existe campo de distância no DTO de entrada.
2. **A trilha é imutável.** `checkin` é append-only por `GRANT`/`REVOKE`, não por convenção de
   código: aceitos e rejeitados ficam gravados com coordenada, acurácia, distância medida, flag de
   mock, velocidade implícita e motivo. É evidência forense que nem a aplicação apaga.
3. **`suspeito` alimenta revisão**, sem bloquear ninguém automaticamente.
4. **Confirmação humana do criador (F7).** Quem sabe se a tarefa foi feita é quem a criou. O
   check-in leva a missão a `AGUARDANDO_CONFIRMACAO` — e não a `CONCLUIDA`. Se o criador discordar,
   `contestar` leva a `EM_DISPUTA`, resolvida por ADMIN.
5. **Dinheiro só se move em `CONCLUIDA`.** Nenhum caminho credita carteira no check-in. Uma fraude de
   check-in isolada, sem passar pela confirmação humana, **não move um centavo**. É a propriedade
   mais importante deste desenho, e ela é estrutural: não depende de nenhum controle antifraude ter
   funcionado.

---

## 5. Nota sobre o ambiente de teste

O append-only de `checkin` é aplicado por `REVOKE UPDATE, DELETE ... FROM omnitribo_app` (V4). **Essa
proteção não é exercida pela suíte de testes**: o Testcontainer conecta como superusuário do
PostgreSQL, para quem o `REVOKE` não vale — os testes de limpeza, aliás, dependem disso para apagar
as próprias linhas.

Consequência prática: não existe teste afirmando "UPDATE em `checkin` falha", e não deve existir —
passaria pelo motivo errado, dando confiança falsa. A garantia depende de o usuário da aplicação em
produção ser `omnitribo_app`, e não um superusuário. Isso é configuração de infraestrutura, e é onde
ela precisa ser verificada.

---

## Referências no código

- `geolocalizacao/dominio/AvaliacaoAntifraude.java` — as regras, como funções puras
- `geolocalizacao/dominio/RegistroCheckinService.java` — gravação e idempotência
- `missoes/dominio/MissaoService.registrarCheckin` — ordem 403 → replay → 409 → gravação
- `missoes/api/ResultadoRegistroCheckin.java` — por que a rejeição volta como valor, não exceção
- `missoes/api/CheckinConcorrenteTest.java` — 50 threads, as duas corridas
- `compartilhado/infra/ConsultasGeoespaciais.java` — única classe com PostGIS (ADR 0007)
- `db/migration/V4__geolocalizacao.sql`, `V12__checkin_idempotencia.sql` — trilha e chave
- `missoes/api/CheckinControllerTest.java` — 14 casos, incluindo o 422 cuja linha sobrevive
