-- Extensões obrigatórias. Em produção, executadas pelo DBA como superuser antes da primeira migração.
-- Em desenvolvimento (Docker Compose) e testes (Testcontainers), o usuário é superuser por padrão.
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
