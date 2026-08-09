# 0011 — Dependências externas (clima e CEP) e anonimização no lugar de exclusão

**Data:** 2026-08-08
**Status:** Aceito

---

## Contexto

O app precisa de duas telas que o backend não sustentava, e de três direitos do titular que nunca
foram implementados:

1. **Card de clima no mapa** e **preenchimento de endereço por CEP** na criação de missão. Os dois
   dependem de dados que não são nossos e que não têm como ser derivados de nada que guardamos.
2. **Exportar dados, gerenciar consentimentos e excluir a conta** (LGPD art. 18, V e VI). A tabela
   `consentimento` existe desde a V2 e nunca teve um caminho de escrita; exclusão de conta nunca
   existiu.

As duas coisas colidem com premissas do projeto. O `CLAUDE.md` afirma **desenvolvimento 100%
local** — nenhuma dependência externa em runtime até aqui. E `lancamento`, `auditoria` e `checkin`
são **append-only**, com a conservação de TOKEN
(`SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens)`) como invariante do sistema: um `DELETE FROM
usuario` quebraria a integridade referencial do ledger e faria essa soma deixar de fechar.

---

## Decisão

### 1. Clima e CEP entram por um módulo próprio, `integracoes`, atrás da nossa fronteira

Adotamos **Open-Meteo** (clima) e **ViaCEP** (endereço), consumidos por `RestClient` a partir de um
módulo novo `integracoes`, com `api`/`dominio`/`infra` e sujeito à mesma regra do ArchUnit dos
demais módulos de negócio.

Os dois provedores foram escolhidos por **não exigirem chave de API**. Isso não é conveniência: uma
chave significaria um segredo novo no `.env`, um item a mais no `.env.example`, e um caminho de
rotação que não temos. O critério de seleção foi esse antes de qualquer comparação de qualidade de
dado.

O app **nunca fala com o provedor direto**. Três consequências que sustentam a decisão:

- a política de cache e o limite de chamadas ficam do lado que controlamos — um app instalado não
  pode ser corrigido;
- o vocabulário chega ao cliente já traduzido para o nosso (`localidade` → `cidade`, código WMO →
  texto em português), então trocar de provedor não obriga a publicar versão nova na loja;
- se um dia houver chave, ela nunca esteve embutida no aplicativo.

**Três defesas obrigatórias, todas verificadas por teste:**

| Defesa | Valor | Por quê |
|---|---|---|
| Timeout | 2 s, conexão e leitura | Sem ele o default é esperar indefinidamente. Um provedor que aceita a conexão e não responde prende a thread da requisição; sob carga isso esgota o pool do servlet e **o card de clima derruba o login**. |
| Cache | Clima 10 min por coordenada arredondada a 2 casas (~1,1 km); CEP permanente, só com teto de entradas | Sem o arredondamento o cache é inútil por construção: cada tremida do GPS gera chave nova. Para CEP, o NEGATIVO também é cacheado — corrigir um CEP errado dispara uma chamada por tecla, e digitar errado é o caso mais frequente num formulário. |
| Degradação | **503** com `TipoProblema.SERVICO_EXTERNO_INDISPONIVEL` | URI própria, não `erro-interno`. A reação de UI é específica: o card de clima **some** e o campo de endereço continua editável à mão. Tratar como 500 faria a tela mostrar "erro inesperado" para uma degradação prevista. |

A falha **não é cacheada** (`Cache.get` não guarda quando a carga lança): cachear indisponibilidade
prolongaria uma interrupção de segundos pelo TTL inteiro.

O 404 de "CEP não existe" e o 503 de "provedor fora do ar" são **`type` diferentes**, porque pedem
ações opostas do usuário: corrigir o número, ou tentar de novo mais tarde. Confundi-los faria alguém
reescrever um CEP correto várias vezes.

### 2. Exclusão de conta é ANONIMIZAÇÃO, não `DELETE`

Ao exercer o direito ao esquecimento, a linha em `usuario` **permanece** e é descaracterizada:

- `nome` → `"Usuário removido"`;
- `email` e `handle` → valores derivados de UUID (as duas colunas são `UNIQUE`; um literal faria a
  segunda exclusão violar a constraint);
- `senha_hash` → hash de um segredo aleatório e descartado;
- `status` → `INATIVO`; `anonimizado_em` → agora (coluna criada na **V18**);
- todos os refresh tokens vivos são revogados, na mesma transação.

O ledger permanece íntegro e a conservação de TOKEN continua fechando. O que desaparece é o vínculo
entre os fatos contábeis e uma pessoa identificável — que é exatamente o que o direito ao
esquecimento pede, lido contra a obrigação de retenção.

A operação exige a **senha atual** no corpo. O access token vive 15 minutos: um aparelho
desbloqueado esquecido em cima da mesa não pode bastar para apagar a identidade de alguém. A dupla
confirmação na tela protege contra o toque acidental; a senha protege contra a pessoa errada. São
defesas diferentes e nenhuma substitui a outra. Repetir a exclusão é **no-op idempotente** — um
retry de rede sobre operação irreversível não pode devolver erro.

`GET /usuarios/me` filtra contas anonimizadas e responde 404: sem isso, um access token ainda vivo
exibiria "Usuário removido" com o XP intacto.

### 3. A exportação usa uma porta com plugins, não um serviço central

`compartilhado/api/DadosPessoaisDoUsuario` é implementada por cada módulo que guarda dado do
titular (`identidade`, `missoes`, `carteira`, `geolocalizacao`), e o Spring injeta a lista.

A alternativa óbvia — um serviço que lê tudo — exigiria alcançar `dominio` e `infra` alheios, que a
regra do ArchUnit proíbe. A segunda alternativa — uma porta nomeada por módulo — faria `identidade`
depender de `missoes`, **que já depende de `identidade`**: um ciclo entre módulos por causa de um
relatório. Com a porta em `compartilhado`, quem monta o arquivo não nomeia módulo nenhum.

O que **não** sai na exportação, e por quê: `senha_hash` (força bruta offline entregue de bandeja,
num arquivo que a pessoa guarda sem cuidado), `chave_idempotencia` (permite forjar replay de
operação de valor), `contraparte_carteira_id` (dado do OUTRO titular) e coordenadas de check-in (a
regra do ADR 0007 mantém todo `ST_*` numa classe só, e "você estava a 12 m da origem da missão X"
responde à pergunta sem materializar um rastro de localização).

### 4. Consentimento é append-only

Cada mudança grava uma **linha nova**; o estado atual é a mais recente por tipo. Sobrescrever seria
mais simples e destruiria a única evidência que importa numa disputa: que a pessoa consentiu em tal
data, sob tal versão do texto. A `versaoTexto` vem do CLIENTE, porque é a versão que ele realmente
viu na tela — preenchê-la no servidor registraria a versão vigente no momento da gravação, e um
deploy no meio faria o registro afirmar que ela concordou com um texto que nunca leu.

O campo `ip` fica **nulo**. A coluna existe e seria fácil preenchê-la com `X-Forwarded-For`, mas
esse header é escolhido pelo cliente enquanto não houver um proxy reverso confiável — gravar um
valor forjável *como evidência* é pior que não gravar nada, porque parece prova.

---

## Consequências

**Positivas**

- Duas telas deixam de ser impossíveis, e as Pendências #3 e #4 do `CLAUDE.md` fecham.
- A suíte não toca a rede: os clientes são testados com `MockRestServiceServer`, e os endpoints com
  fontes dublê. Um teste que exigisse internet seria vermelho intermitente sem informação.
- `logistica` e `notificacoes` deixam de ser módulos sem caminho de leitura.
- A exportação passa a incluir automaticamente qualquer módulo futuro que publique o bean.

**Negativas / trade-offs**

- **O "100% local" passa a ter exceção.** Sem internet, clima e CEP respondem 503 e as telas
  degradam. O núcleo — auth, missões, check-in, carteira — continua íntegro e local.
- Dependemos da disponibilidade e da estabilidade de contrato de dois terceiros. O timeout e o cache
  contêm o dano; uma mudança de schema do provedor derruba a seção correspondente e é detectada
  pelos testes de borda, não em produção.
- Esquecer de implementar `DadosPessoaisDoUsuario` num módulo novo **não quebra nada** — a
  exportação sai incompleta em silêncio. É a mesma classe de armadilha do `@Auditavel`, e a defesa é
  o teste que trava as seções conhecidas.
- Anonimizar em vez de apagar é defensável juridicamente, mas exige explicação: alguém pode ler
  "linha ainda existe" como "não apagaram meus dados". A resposta é o conteúdo da linha.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|---|---|
| **Chamar Open-Meteo e ViaCEP direto do app** | Uma eventual chave de API ficaria embutida num binário que não se corrige; a tradução de vocabulário do provedor viveria no cliente, e trocar de provedor exigiria publicar versão nova na loja para todo mundo. Também impediria qualquer cache compartilhado entre usuários. |
| **Clima e CEP em `compartilhado`** | `compartilhado` é infraestrutura usada por outros módulos. Um proxy de previsão do tempo não é isso, e o módulo é isento da regra do ArchUnit como alvo — esconder integrações externas ali as tiraria da verificação de fronteira. |
| **Provedor de clima com chave (OpenWeather e similares)** | Introduziria o primeiro segredo de terceiro no projeto, com rotação e `.env` a manter, para um card decorativo. |
| **Stub local de clima e CEP** | Manteria o "100% local" e tornaria as duas telas mentira: um CEP que não resolve endereço real não preenche formulário nenhum, e o card de clima viraria enfeite fixo. |
| **`DELETE FROM usuario` na exclusão de conta** | Quebra a FK do ledger append-only e a conservação de TOKEN. Um crédito que existiu não deixa de ter existido porque o titular pediu para ser esquecido. |
| **Soft delete só com `status = INATIVO`** | 'INATIVO' já significa "conta desativada", que é reversível e acontece por vários motivos. Não distingue desativação de pedido do titular, e não registra QUANDO o pedido foi atendido — que é a pergunta a responder numa fiscalização. |
| **Exclusão sem reconfirmação de senha** | O access token vive 15 minutos e sobrevive ao aparelho trocar de mãos. Uma operação irreversível protegida só por um toque é uma operação irreversível desprotegida. |
| **Uma porta de exportação por módulo (`missoes/api/DadosDeMissao` etc.)** | Faria `identidade` depender de `missoes`, que já depende de `identidade` — ciclo entre módulos por causa de um relatório. |
| **Sobrescrever a linha de consentimento** | Destrói a evidência de quando e sob qual versão do texto a escolha foi feita, que é a única coisa que um registro de consentimento existe para provar. |
