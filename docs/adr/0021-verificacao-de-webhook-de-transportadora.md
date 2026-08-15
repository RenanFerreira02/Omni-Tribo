# 0021 — Verificação de webhook de transportadora

**Data:** 2026-08-14
**Status:** Aceito

---

## Contexto

`POST /api/v1/webhooks/transportadora` é o **único endpoint de escrita da API que não exige JWT**, e
o que ele faz não é pouco: cria missão publicada, movimenta a ocupação de um ponto de custódia e
dispara notificação para membros de tribo. Quem conseguir forjar uma requisição consegue poluir o
radar do bairro inteiro e imobilizar vagas físicas.

O código já carregava a instrução do que fazer. `SecurityConfig` tinha `/api/v1/webhooks/**` fora do
`permitAll`, com este comentário:

> Era o único `/**` da cadeia, e o comentário ao lado afirmava que o HMAC "foi implementado na F10" —
> não foi (…). Quando o webhook chegar, **ele nasce autenticado e a isenção é reintroduzida JUNTO com
> o HMAC, não antes.**

Ou seja: uma versão anterior do projeto declarou uma proteção que não existia, e a correção foi
fechar a rota até que existisse. Este ADR registra a proteção que a reabre.

---

## Decisão

**Autenticamos o webhook por HMAC-SHA256 sobre o CORPO BRUTO**, com quatro propriedades:

1. **Sobre os bytes que chegaram no fio**, nunca sobre o objeto desserializado. A requisição é
   bufferizada por um wrapper próprio e repassada intacta ao controller.
2. **O carimbo de tempo entra DENTRO do material assinado**: `HMAC(segredo, timestamp + "." + corpo)`,
   com janela de 5 minutos para os dois lados.
3. **Comparação em tempo constante**, com `MessageDigest.isEqual`.
4. **Um segredo POR TRANSPORTADORA**, em configuração (`app.webhooks.segredos.<slug>`), alimentado
   por variável de ambiente.

**Toda falha responde o mesmo 401 `nao-autenticado`**, sem distinguir a causa.

**A identidade da transportadora é publicada como atributo VERIFICADO da requisição**
(`AtributosWebhook.TRANSPORTADORA`), e o controller lê de lá — nunca do cabeçalho cru nem do corpo.

O filtro tem **teto de requisições próprio, por transportadora**, e `/api/v1/webhooks/` é isento do
`RateLimitFilter` geral.

---

## Consequências

**Positivas:**

- Corpo bruto elimina toda uma classe de divergência: assinar o objeto reserializado compararia o
  resultado de uma normalização NOSSA — ordem de chaves, espaços, precisão numérica, escapes Unicode
  — com uma assinatura calculada sobre o texto DELES. Divergência aí vira 401 intermitente, e a
  "correção" natural é afrouxar a comparação, que é como uma verificação de integridade deixa de
  verificar.
- O carimbo dentro do material assinado é o que torna a janela útil. Fora dele, o atacante trocaria o
  cabeçalho por um instante atual e reenviaria o corpo com a assinatura original.
- Segredo por transportadora impede que um parceiro integrado grave encomendas em nome de outro. Há
  teste dedicado para isso: assinar com o segredo válido de A declarando-se B é 401.
- Segredo em configuração e não em tabela: segredo em tabela é segredo em backup, em dump de suporte
  e em qualquer `make psql`. O banco tem dois papéis de conexão e nenhum deles precisa conhecer
  material de autenticação de parceiro.
- Ler a transportadora de um atributo verificado, e não do cabeçalho, torna impossível o erro
  silencioso: aceitar a alegação sem a prova continuaria funcionando no caminho feliz.

**Negativas / trade-offs:**

- **Cadastrar transportadora nova exige deploy.** Aceito: a lista muda em ritmo de contrato
  comercial, não de operação.
- O 401 indistinguível dificulta o diagnóstico do parceiro durante a integração. Mitigado por log do
  lado do servidor com o motivo real — nunca no corpo da resposta.
- Um segredo compartilhado não dá não-repúdio: qualquer um que o tenha pode assinar. Assinatura
  assimétrica resolveria, ao custo de gestão de chaves com um parceiro que provavelmente só suporta
  HMAC.
- Requer relógios razoavelmente sincronizados dos dois lados.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| HMAC sobre o objeto desserializado e reserializado | Compara a nossa normalização com o texto original do parceiro. Qualquer diferença de serialização vira falha intermitente, e a pressão para "resolver" é afrouxar a checagem. |
| `ContentCachingRequestWrapper` do Spring | Guarda o que foi lido para inspeção POSTERIOR. Como a verificação precisa acontecer ANTES de qualquer desserialização, quem consome o stream primeiro deixaria o controller com EOF. |
| Carimbo de tempo fora do material assinado, só como cabeçalho | Replay trivial: capturar uma requisição válida e reenviá-la com timestamp novo. A janela não protegeria nada. |
| Comparar assinaturas com `String.equals` | Sai no primeiro byte diferente. A diferença de tempo é medível pela rede e permite reconstruir a assinatura byte a byte sem nunca conhecer o segredo. |
| Um segredo global para todas as transportadoras | Qualquer parceiro integrado poderia gravar encomendas em nome de todos os outros, e revogar o segredo de um exigiria trocar o de todos. |
| Segredo em tabela, com endpoint de administração | Coloca credencial de parceiro no banco, e portanto em backup e dump. Ganharia cadastro sem deploy — que não é um problema que temos. |
| Token estático em cabeçalho (`Authorization: Bearer <chave>`) | Não cobre o corpo: quem interceptar a chave reproduz qualquer payload. O HMAC liga a autenticação ao conteúdo exato daquela requisição. |
| mTLS | Mais forte, e desproporcional para um projeto acadêmico rodando 100% local — exigiria PKI, distribuição de certificados e terminação TLS que o ambiente não tem. |
| URI de erro própria para cada causa de 401 | Contaria a quem não tem o segredo qual etapa ele já venceu: "timestamp velho" avisa que a assinatura estava certa. Mesma doutrina do 401 de login e de `ConsultaSessao`. |
