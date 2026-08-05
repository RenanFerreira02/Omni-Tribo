-- =============================================================================
-- V2 — Módulo identidade
-- Tabelas: tribo, usuario, consentimento, refresh_token, dispositivo, auditoria
-- =============================================================================

CREATE TABLE tribo (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    nome        VARCHAR(100) NOT NULL,
    bairro      VARCHAR(100) NOT NULL,
    criada_em   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE usuario (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    nome          VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    senha_hash    VARCHAR(255) NOT NULL,
    handle        VARCHAR(50)  NOT NULL,
    tribo_id      UUID         REFERENCES tribo(id),
    xp            BIGINT       NOT NULL DEFAULT 0,
    nivel         INT          NOT NULL DEFAULT 1,
    streak        INT          NOT NULL DEFAULT 0,
    -- numeric(2,1): valores de 0.0 a 5.0; precisão 2, escala 1
    rating        NUMERIC(2,1) NOT NULL DEFAULT 0.0,
    papel         VARCHAR(10)  NOT NULL CHECK (papel IN ('USUARIO', 'ADMIN')),
    status        VARCHAR(10)  NOT NULL CHECK (status IN ('ATIVO', 'INATIVO', 'SUSPENSO', 'BANIDO')),
    criado_em     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    atualizado_em TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    versao        INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_usuario_email  UNIQUE (email),
    CONSTRAINT uk_usuario_handle UNIQUE (handle)
);

-- LGPD art. 8º — registro de consentimentos do titular com versão do texto aceito
CREATE TABLE consentimento (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id   UUID        NOT NULL REFERENCES usuario(id),
    tipo         VARCHAR(15) NOT NULL CHECK (tipo IN ('LOCALIZACAO', 'NOTIFICACAO', 'TERMOS')),
    concedido    BOOLEAN     NOT NULL,
    versao_texto VARCHAR(20) NOT NULL,
    -- IPv4 ou IPv6; 45 cobre ::ffff:192.0.2.128
    ip           VARCHAR(45),
    criado_em    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Suporte a rotação de refresh tokens com detecção de reuso (RFC 6819 §5.2.2.3).
-- substituido_por é UUID puro sem FK: evita constraint circular e permite leitura
-- de tokens já expirados/revogados para auditoria sem risco de cascade delete.
CREATE TABLE refresh_token (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id       UUID        NOT NULL REFERENCES usuario(id),
    token_hash       VARCHAR(255) NOT NULL,
    familia_id       UUID        NOT NULL,
    expira_em        TIMESTAMPTZ NOT NULL,
    revogado_em      TIMESTAMPTZ,
    substituido_por  UUID        -- UUID puro: sem FK deliberado (ver comentário acima)
);

CREATE TABLE dispositivo (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id      UUID        NOT NULL REFERENCES usuario(id),
    push_token      VARCHAR(255) NOT NULL,
    plataforma      VARCHAR(10) NOT NULL CHECK (plataforma IN ('IOS', 'ANDROID')),
    ultimo_visto_em TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_dispositivo_push_token UNIQUE (push_token)
);

-- APPEND-ONLY — trilha de auditoria para conformidade com LGPD e rastreabilidade de segurança.
-- Nunca deve sofrer UPDATE ou DELETE: correções são registradas como novas linhas.
-- ator_id é nullable para capturar ações anônimas (tentativas de login, acesso sem autenticação).
CREATE TABLE auditoria (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    ator_id        UUID,       -- null = ação anônima ou do sistema
    acao           VARCHAR(100) NOT NULL,
    entidade       VARCHAR(100) NOT NULL,
    entidade_id    VARCHAR(255),
    ip             VARCHAR(45),
    user_agent     VARCHAR(500),
    correlation_id VARCHAR(36),
    criado_em      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =============================================================================
-- Usuário de aplicação com privilégios mínimos (princípio do menor privilégio).
-- Em produção, o backend conecta como omnitribo_app; o usuário DDL é separado.
-- O bloco condicional garante idempotência (Flyway re-executa em reset de dev).
-- =============================================================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'omnitribo_app') THEN
        CREATE ROLE omnitribo_app WITH LOGIN PASSWORD 'omnitribo_app_dev';
    END IF;
END$$;

GRANT SELECT, INSERT, UPDATE, DELETE ON tribo       TO omnitribo_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON usuario     TO omnitribo_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON consentimento TO omnitribo_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON refresh_token TO omnitribo_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON dispositivo  TO omnitribo_app;

-- auditoria é APPEND-ONLY: REVOKE UPDATE e DELETE impede adulteração da trilha de auditoria
-- mesmo que o código da aplicação tente executá-los (defesa em profundidade).
GRANT  SELECT, INSERT ON auditoria TO omnitribo_app;
REVOKE UPDATE, DELETE ON auditoria FROM omnitribo_app;
