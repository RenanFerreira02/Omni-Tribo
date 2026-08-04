# 0002 — PostgreSQL + PostGIS em vez de Cloud Firestore

**Data:** 2026-08-04  
**Status:** Aceito

---

## Contexto

O protótipo Flutter usava Cloud Firestore. Firestore é um banco NoSQL com consistência eventual,
sem suporte a transações ACID entre documentos de coleções distintas e sem suporte nativo a consultas
geoespaciais complexas (raio, distância entre pontos). O Omni-Tribo tem dois requisitos que entram
em conflito direto com essas limitações:

1. **Ledger financeiro:** lançamentos de BRL e TOKEN exigem ACID rigoroso — débito e crédito devem
   ser atômicos ou nenhum acontece.
2. **Missões hiperlocais:** descoberta de missões por raio, validação de check-in geolocalizado e
   cálculo de distância são operações de primeiro cidadão, não consultas em memória.

---

## Decisão

Adotamos PostgreSQL 16 com a extensão PostGIS habilitada (`CREATE EXTENSION postgis`).  
O banco roda em Docker local; o schema é gerenciado exclusivamente pelo Flyway (`ddl-auto: validate`).

**Isolamento geoespacial:** toda consulta que usa funções PostGIS (`ST_DWithin`, `ST_Distance`,
`ST_SetSRID`, etc.) fica isolada em uma única classe de repositório por módulo. Isso cria um
anti-corruption layer que torna a troca do motor geoespacial uma mudança de arquivo único.

**Parceria FIAP-Oracle:** a FIAP mantém parceria com a Oracle. Se essa parceria vier a ser usada
no projeto, a migração para Oracle Spatial é viável exatamente porque o isolamento acima já existe —
Oracle Spatial expõe funções equivalentes (`SDO_WITHIN_DISTANCE`, `SDO_GEOM.SDO_DISTANCE`).

---

## Consequências

**Positivas:**
- Transações ACID garantem que débito e crédito do ledger são atômicos.
- `ST_DWithin` com índice GIST é eficiente o suficiente para o volume esperado.
- `geography(POINT, 4326)` armazena coordenadas com semântica esférica correta; distâncias em
  metros sem conversão manual.
- Integridade referencial real via FK (exceto `carteira → missao_id`, deliberadamente UUID puro
  para desacoplar o módulo de carteira do de missões).

**Negativas / trade-offs:**
- Requer Docker local; não há banco gerenciado gratuito com PostGIS fácil de configurar.
- Queries geoespaciais exigem índice GIST explícito nas migrations — esquecê-lo degrada para seq scan.

---

## Alternativas descartadas

| Alternativa | Por que foi descartada |
|-------------|------------------------|
| Cloud Firestore (protótipo) | Consistência eventual incompatível com ledger financeiro; sem suporte nativo a consultas geoespaciais por raio com índice. |
| MongoDB com índice 2dsphere | Sem transações ACID multi-documento antes do v4; ecossistema Spring Data Mongo menos maduro para JPA/Flyway. |
| SQLite + extensão geoespacial | Sem suporte adequado a concorrência multi-thread; não adequado para simular ambiente de produção. |
