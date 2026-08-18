# 0005 — Autenticação com JWT RS256 + Argon2id + Refresh Token com Rotação de Família

**Data:** 2026-08-05  
**Status:** Aceito

---

## Contexto

F2 introduz autenticação completa. As decisões envolvem: algoritmo de hash de senha, algoritmo de
assinatura de JWT, estratégia de refresh token, e proteções contra os ataques mais comuns em APIs
REST (credential stuffing, enumeração de usuários, roubo de sessão).

---

## Decisão

**Senha**: Argon2id via `DelegatingPasswordEncoder`. Argon2id é a recomendação do OWASP para novos
sistemas (memory-hard, resistente a GPU/ASIC). `DelegatingPasswordEncoder` permite migrar hashes
legados `{bcrypt}` sem re-login, identificando o algoritmo pelo prefixo no hash armazenado.

**JWT**: RS256 (RSA 2048 bits). Chave privada assina; chave pública verifica. Em arquitetura futura
com múltiplos serviços, qualquer serviço pode verificar tokens com a chave pública sem acesso à
privada. Claims incluem `sub` (userId), `papel`, `jti` (revogação futura), `iss`, `aud`, `exp` (15
min). TTL curto minimiza janela de comprometimento de tokens válidos sem revogação no servidor.

**Refresh token**: Valor opaco de 256 bits (SecureRandom), armazenado apenas como `sha256(token)` no
banco. A cada uso, o token é rotacionado: o antigo é revogado e um novo é emitido na mesma
`familia_id`. Detecção de reuso (RFC 6819 §5.2.2.3): se um token já revogado é apresentado, toda a
família é revogada — força novo login, protegendo o usuário legítimo caso o token tenha sido roubado
após uma rotação. A rotação usa `PESSIMISTIC_WRITE` no `findByTokenHash` para serializar acesso
concorrente e garantir atomicidade da operação.

**Rate limiting**: Bucket4j (token bucket, 5 tentativas de login por minuto por chave
`sha256(ip+email)`). Após 10 falhas em 15 minutos: bloqueio progressivo de 15 minutos.

**Mensagem genérica em login**: Senha errada e email inexistente retornam a mesma resposta (status e
corpo idênticos) — previne enumeração de usuários via timing ou conteúdo de resposta.

---

## Consequências

**Positivas:**
- Argon2id é significativamente mais resistente a brute-force offline do que bcrypt/scrypt em
  hardware moderno
- RS256 permite verificação distribuída sem expor a chave privada
- Rotação de refresh token com detecção de reuso isola sessões comprometidas sem afetar todas as
  sessões do usuário
- `DelegatingPasswordEncoder` garante migração transparente de hashes legados

**Negativas / trade-offs:**
- RS256 tem overhead maior que HS256 (~3× em operações de assinatura); aceitável dado o TTL de 15
  min (poucas emissões por sessão)
- Argon2id aumenta CPU em ~100ms por login/registro; mitigado pelo rate limiting que limita a taxa
  de autenticações
- Detecção de reuso pode causar logout forçado do usuário legítimo se um token intermediário for
  roubado e rotacionado pelo atacante antes do usuário usar o seu — trade-off deliberado em favor de
  segurança
- Bloqueio in-memory (BloqueioLoginService) não sobrevive a restart nem funciona em múltiplas
  instâncias; aceitável para MVP single-instance

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| HS256 (HMAC compartilhado) | Chave compartilhada: todos os serviços que verificam tokens precisam da chave secreta, ampliando a superfície de comprometimento. RS256 permite verificação sem acesso à chave privada. |
| BCrypt exclusivo sem DelegatingPasswordEncoder | Impediria migração futura para Argon2id sem forçar reset de senha de todos os usuários. |
| Refresh token sem rotação (token fixo) | Token roubado é válido indefinidamente até expirar ou ser explicitamente revogado. Rotação limita a janela de comprometimento a uma única sessão. |
| Refresh token com revogação stateful sem família | Não detectaria reuso de tokens intermediários — um atacante que rotaciona o token antes do dono não seria identificado. |
| Redis para rate limiting e blacklist de refresh | Deliberadamente excluído do MVP (ver CLAUDE.md e ADR 0001). Migração é possível trocando BloqueioLoginService. |
| JWT com TTL longo (ex: 24h) sem refresh | Comprometimento de um access token dura 24h sem possibilidade de revogação sem servidor de estado. TTL de 15 min minimiza a janela. |
