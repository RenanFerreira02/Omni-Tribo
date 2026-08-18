# 0010 — Granularidade do catálogo de tipos de problema

**Data:** 2026-08-08  
**Status:** Aceito — refina o catálogo `TipoProblema` introduzido junto ao [ADR 0005](./0005-autenticacao-jwt-argon2.md)

---

## Contexto

Toda resposta de erro da API é um `ProblemDetail` (RFC 9457) e carrega um `type` do catálogo
`compartilhado/api/TipoProblema`. A regra que o app segue é dura e está escrita em
`apps/mobile/CLAUDE.md`: **discrimine pelo `type`, nunca pelo `detail`**. `detail` é texto em
português voltado a humano, muda a cada revisão de copy, e um `if` sobre ele quebra sem quebrar
teste nenhum.

Até aqui o catálogo tinha **uma URI por CLASSE de erro**: todo 422 era `regra-negocio-violada`, todo
409 de estado era `transicao-invalida`, e assim por diante. Isso funcionou enquanto não havia
cliente. Ao desenhar as primeiras telas do app (F9–F11), a granularidade deixou de fechar em quatro
pontos concretos:

- **As três rejeições de check-in.** `AvaliacaoAntifraude` recusa por localização simulada, por
  acurácia insuficiente ou por distância acima do raio. As três são 422 com o mesmo `type`, e as
  três exigem instruções **diferentes e mutuamente inúteis**: "desligue a localização simulada",
  "procure céu aberto" e "aproxime-se do local". Mandar quem esqueceu o mock ligado caminhar até o
  local é fazê-lo falhar de novo no mesmo ponto — e o código já tinha uma ordem de precedência fixa
  justamente para não cometer esse erro na mensagem. O que faltava era o app poder agir sobre ela.
- **O saque desligado por configuração.** `app.carteira.saque-habilitado` é `false` desde o
  [ADR 0009](./0009-economia-do-cuidado-token-como-recompensa.md). A recusa chegava ao app
  indistinguível de "saldo insuficiente", e a tela sugeriria ao usuário juntar saldo para uma
  operação que não vai reabrir.

O `CLAUDE.md` registrava isso como Pendência #4 e mandava decidir **antes da primeira tela de erro**,
porque decidir depois significa refazer o tratamento de erro no mobile.

---

## Decisão

**Adotamos como critério de granularidade a REAÇÃO DE UI, não a classe do erro nem a causa.**

Uma causa ganha `type` próprio quando **a tela faz algo diferente por causa dela**. Não ganha quando
a tela apenas exibe o `detail` — nesse caso a URI genérica já carrega toda a informação que o
cliente usa, e uma URI a mais só aumentaria a superfície de contrato sem ninguém consumi-la.

Aplicando o critério, o catálogo ganha quatro URIs:

| URI | Status | Reação da tela |
|---|---|---|
| `checkin-localizacao-simulada` | 422 | instruir a desligar o mock; nenhum deslocamento resolve |
| `checkin-acuracia-insuficiente` | 422 | instruir a buscar céu aberto e tentar de novo no mesmo lugar |
| `checkin-fora-do-raio` | 422 | mostrar a distância e pedir aproximação |
| `saque-desabilitado` | 422 | estado próprio de recurso fechado, não alerta de erro |

E **`regra-negocio-violada` permanece o 422 padrão** — saldo insuficiente, pote insuficiente, janela
vencida, tribo alheia. Todos exibem `detail`, todos fazem a mesma coisa.

**Nenhum status HTTP muda.** Os quatro continuam 422; o que muda é só o `type`. A ordem de checagem
403 → 409 → 422 e a inserção da sondagem de idempotência no meio do check-in ficam intactas. A
implementação é a que o próprio javadoc de `TipoProblema` já indicava: subclasse de
`DominioException` sobrescrevendo `getTipo()` — `CheckinRejeitadoException` (uma classe, com
`switch` sobre `MotivoRejeicaoCheckin`) e `SaqueDesabilitadoException`.

**O código da rejeição é PERSISTIDO, não derivado** (`checkin.codigo_rejeicao`, V17). Essa é a parte
não óbvia. Um check-in rejeitado pode ser relido pela chave de idempotência, e nesse replay o
veredito é reconstruído a partir da linha gravada, sem reavaliar nada. Sem a coluna, a primeira
tentativa responderia `checkin-fora-do-raio` e o retry de rede responderia o 422 genérico — **dois
contratos para a mesma operação**, com o app tratando o retry como um erro diferente do original. A
constraint `ck_checkin_rejeicao_coerente` amarra código e `valido` para que o banco não admita a
linha que reabriria o problema.

O texto humano continua em `motivo_rejeicao`. Os dois campos descrevem a mesma decisão em dois
registros: um estável para máquina, um mutável para gente.

---

## Consequências

**Positivas:**

- O app pode dar a instrução CERTA em cada rejeição de check-in, que é a diferença entre o usuário
  concluir a missão e desistir dela.
- A proibição de parsear `detail` deixa de ser um pedido de disciplina impossível de cumprir: agora
  existe o caminho legítimo para o que as telas precisavam.
- A copy volta a ser livre. Reescrever "Você está a 340 m da origem" não pode mais quebrar cliente
  nenhum, porque nada depende do texto.
- O replay de uma rejeição passa a ter contrato idêntico ao da primeira tentativa — verificado por
  teste, e não por leitura.

**Negativas / trade-offs:**

- O catálogo vira um contrato maior, e URI publicada não muda mais: renomear a constante é refactor,
  mudar o texto da URI é quebra com todo app instalado.
- Uma migration a mais (V17) e, com ela, `make reset` em toda base de dev — custo já rotineiro por
  causa da faixa 900 do seed.
- O critério "reação de UI" exige julgamento e vai ser discutido de novo a cada erro novo. É
  preferível ao critério mecânico, que erra dos dois lados: "uma por classe" foi o que produziu esta
  pendência, e "uma por causa" produziria um catálogo enorme e majoritariamente morto.
- `AvaliacaoAntifraude` passa a devolver a causa duas vezes (código e texto). É redundância
  deliberada, e as duas precisam continuar coerentes — a constraint do banco cobre a metade que
  importa.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| **Manter uma URI por classe e o app ramificar pelo `detail`** | É exatamente o que `apps/mobile/CLAUDE.md` proíbe, e pelo motivo certo: o `detail` é copy. Um `if (detail.includes("simulada"))` passa em todo teste no dia em que é escrito e quebra em silêncio na primeira revisão de texto — sem erro de compilação, sem teste vermelho, sem log. O bug apareceria como "o app às vezes dá a dica errada". |
| **Uma URI por causa, mecanicamente, em todo o catálogo** | Multiplicaria o contrato por cada `throw new RegraNegocioVioladaException` do código — dezenas de URIs para casos em que a tela faz a mesma coisa. Contrato grande e majoritariamente morto é pior que contrato pequeno: dá a impressão de que o cliente deve tratar cada caso, e cria trabalho de manutenção sem consumidor. |
| **Um campo `codigo` novo no corpo, ao lado do `type`** | Inventaria um segundo eixo de discriminação, paralelo ao que o RFC 9457 já padroniza. O cliente passaria a ter duas coisas para olhar, e a pergunta "qual delas manda?" não teria resposta boa. O `type` existe para isso. |
| **Derivar o código da rejeição em vez de persistir** | Economiza a migration e quebra o replay: a releitura pela chave de idempotência não reavalia, reconstrói. O sintoma seria uma resposta diferente para o mesmo pedido repetido — a falha exatamente no caminho que a idempotência existe para tornar previsível. |
| **Status HTTP distintos (ex.: 409 para fora do raio)** | Estaria errado quanto ao significado: a operação CABE no estado (a missão está EM_ANDAMENTO e o ator é o executor); o que não satisfaz são os dados. Isso é 422 por definição, e o projeto já paga o preço de manter essa distinção nítida. Mudar status por conveniência de roteamento no cliente inverteria o contrato que o app integra. |
| **Três classes de exceção irmãs para o check-in** | As três compartilham status, construtor e semântica; o que as separa é um valor. Três classes só multiplicariam a superfície a manter quando o enum crescesse, sem ganhar nada em expressividade. |
