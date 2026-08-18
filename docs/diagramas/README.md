# Diagramas

Todos em [Mermaid](https://mermaid.js.org), versionados como texto — renderizam direto no GitHub e
são revisáveis em diff, ao contrário de imagem exportada.

**Cada diagrama descreve o que existe no código**, com o arquivo-fonte citado no topo. A única
exceção está marcada como tal.

| Diagrama | O que responde |
|---|---|
| [`c4-contexto-e-conteineres.md`](c4-contexto-e-conteineres.md) | C4 níveis 1 e 2 **do que foi construído**: quem usa, o que conversa com o quê, quais portas |
| [`arquitetura-alvo.md`](arquitetura-alvo.md) | ⚠️ **visão de produção, NÃO implementada** — borda TLS/WAF, balanceador, N instâncias, broker, observabilidade, e a ordem de decomposição |
| [`maquina-estados.md`](maquina-estados.md) | os 9 estados e as 17 transições da missão, com o ator de cada uma |
| [`sequencia-ciclo-missao.md`](sequencia-ciclo-missao.md) | aceitar → iniciar → check-in → confirmar, mostrando **o que está dentro da transação** e onde entra a outbox |
| [`sequencia-entrega-falida.md`](sequencia-entrega-falida.md) | webhook HMAC → ponto de custódia → missão → notificação, incluindo o desfecho RECUSADA |
| [`fluxo-economico.md`](fluxo-economico.md) | quem financia o pote por categoria, **onde o token é cunhado** e onde deveria ser resgatado |
| [`er-banco.md`](er-banco.md) | as 14 tabelas e as 6 referências propositalmente **sem foreign key** |

## Se um diagrama divergir do código, o código está certo

Estes arquivos são documentação, não fonte de verdade. Ao mudar a máquina de estados, o fluxo de
conclusão ou o schema, atualize o diagrama no mesmo commit — um diagrama desatualizado engana mais
que a ausência dele.
