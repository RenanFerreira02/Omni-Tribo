# 0019 — Borda HTTP: cabeçalho de cliente é entrada não confiável

**Data:** 2026-08-11
**Status:** Aceito

---

## Contexto

Três lugares liam `X-Forwarded-For` por conta própria — `AuthController`, `AuditoriaAspecto` e
`RateLimitFilter` —, cada um com a sua cópia de `extrairIp`. Dois traziam um comentário dizendo que
"em produção é preciso validar que o proxy é confiável"; nenhum validava, e não havia
`server.forward-headers-strategy` nem allowlist de proxy em YAML nenhum.

O efeito não era teórico. A chave do bloqueio progressivo de login é `sha256(ip + ":" + email)`. Com o
IP saindo de um header que o cliente escolhe, **cada requisição podia trazer uma chave nova**: o
bucket de 5/min e o contador de 10 falhas nunca acumulavam, e credential stuffing contra um único
e-mail ficava ilimitado — com o Argon2 do servidor (~100 ms por tentativa) trabalhando contra o
defensor.

Havia uma confirmação involuntária disso na própria suíte: quatro classes de teste usavam
`X-Forwarded-For` para se isolarem umas das outras, ganhando cada uma um bucket de login exclusivo.
A suíte inteira explorava o bypass sem saber.

Em paralelo, os mesmos headers alimentavam colunas estreitas de `auditoria` sem truncamento —
`ip VARCHAR(45)`, `correlation_id VARCHAR(36)` — e nada validava o `X-Correlation-Id` antes do MDC.

---

## Decisão

**Uma única leitura de IP: `compartilhado/api/EnderecoDoCliente.de(request)`, que devolve
`getRemoteAddr()` e NÃO lê header nenhum.**

Quem resolve proxy passa a ser a `RemoteIpValve` do Tomcat, via
`server.tomcat.remoteip.trusted-proxies`: **vazia em dev e test** (ninguém é confiável, o IP é o peer
TCP real), preenchida em produção com o CIDR do proxy de verdade. A confiança no header vira um dado
de AMBIENTE, não uma linha de código — e nenhum call site novo consegue reabrir o buraco por descuido.

**`X-Correlation-Id` passa por allowlist** (`[A-Za-z0-9._-]{1,64}`) antes do MDC; fora disso, o
servidor gera um id novo. Fecha a forja de linha de log por CR/LF — o pattern de console é
`[%X{correlationId}]`, então CR/LF fabricava linhas inteiras, inclusive falsas de `LOGIN_BLOQUEADO`.

**Truncamento no construtor de `Auditoria`**, que é o único choke point dos três gravadores. E a V19
alargou `correlation_id` para 64: um `traceparent` do W3C tem 55 caracteres e é legítimo; truncá-lo em
36 destrói a correlação que é a única razão da coluna existir.

**`DataIntegrityViolationException` ganhou handler ramificado por SQLState**: `22001` (valor maior que
a coluna) vira **400** com `log.warn`; qualquer outro — inclusive `23505` de idempotência — continua
subindo como 500, preservando literalmente a regra do `services/api/CLAUDE.md`.

Sem essas três camadas, um `X-Forwarded-For` de 46 caracteres estourava a coluna com SQLState 22001
durante o flush; e como a auditoria de login grava DENTRO da transação, **todo login virava 500** —
sem autenticação, com uma requisição, e sem precisar de má-fé.

---

## Consequências

**Positivas:**
- O bloqueio de login volta a acumular por origem real. `RateLimitFilter` e `BloqueioLoginService` não
  mudaram uma linha: a correção foi na fonte do IP.
- Um 500 injetável sem autenticação deixa de existir, fechado em três pontos independentes.
- Log forging fechado.
- Um lugar só para revisar quando a topologia de rede mudar.

**Negativas / trade-offs:**
- Produção exige configurar `TRUSTED_PROXIES` corretamente; errado, todos os clientes compartilham o
  IP do balanceador e o bloqueio fica agressivo demais. É o modo de falha SEGURO — a alternativa erra
  para o lado permissivo.
- Quatro classes de teste precisaram trocar o header por `remoteAddr` de verdade (`vindoDe(ip)`).
  Passaram a exercitar o caminho que roda em produção, em vez de um atalho.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| `server.forward-headers-strategy: FRAMEWORK` ou `NATIVE` | Fazem `getRemoteAddr()` honrar `X-Forwarded-For` **incondicionalmente**. Trocaria três leituras inseguras VISÍVEIS por uma leitura insegura embutida no container — pior, porque some do código e ninguém revisa. |
| Manter `extrairIp` em cada call site, com validação de proxy própria | É o estado que produziu a falha, e reimplementaria mal o que a `RemoteIpValve` já faz. Duas cópias tinham o aviso; nenhuma tinha a validação. |
| Rejeitar `X-Correlation-Id` malformado com 400 | Transformaria telemetria malformada em falha de requisição. Gerar um id novo custa nada e o cliente perde só a correlação que ele mesmo estragou. |
| Só truncar em Java, sem alargar a coluna | Um `traceparent` legítimo continuaria sendo cortado ao meio, destruindo a correlação. |
| Só alargar a coluna, sem truncar em Java | A próxima coluna estreita reabriria o mesmo 500. As três camadas são independentes de propósito. |
