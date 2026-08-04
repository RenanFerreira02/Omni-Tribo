# 0001 — Monólito Modular em vez de Microsserviços

**Data:** 2026-08-04  
**Status:** Aceito

---

## Contexto

O Omni-Tribo é um projeto acadêmico com carga estimada de até 1.500 usuários simultâneos no
horizonte do MVP. O protótipo anterior (Flutter + Firestore) não tinha camadas de serviço definidas.
A arquitetura precisava ser escolhida antes de qualquer código de aplicação.

---

## Decisão

Adotamos um monólito modular com sete módulos internos:
`compartilhado · identidade · missoes · geolocalizacao · carteira · logistica · notificacoes`

Cada módulo segue a estrutura `api/ | dominio/ | infra/`. A regra de acesso entre módulos é
verificada em tempo de build pelo ArchUnit: um módulo só acessa outro pela sua camada `api/` pública
ou por evento interno — nunca por repositório ou entidade JPA alheia.

---

## Consequências

**Positivas:**
- Um único processo, deploy simples (JAR + Docker Compose).
- Transações locais — sem sagas, sem 2PC, sem consistência eventual entre serviços.
- ArchUnit garante que as fronteiras não sejam violadas silenciosamente.
- Extração incremental para microsserviços fica viável quando a escala exigir: as fronteiras já
  estão bem definidas.

**Negativas / trade-offs:**
- Deploy atômico: uma mudança em `notificacoes` reimplanta tudo.
- Escalar horizontalmente replica módulos que não precisam de escala (aceitável a 1.500 usuários).
- Disciplina manual para respeitar as fronteiras além do que o ArchUnit consegue verificar.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Microsserviços desde o início | A 1.500 usuários esperados, a decomposição adiciona latência de rede em chamadas internas, exige consistência distribuída (sagas/2PC) e custo operacional (registry, service mesh, múltiplos pipelines de deploy) sem nenhum ganho de capacidade. |
| Monólito sem módulos (big ball of mud) | Impossível verificar fronteiras por ArchUnit; acoplamento cresceria sem controle ao longo das fases. |
