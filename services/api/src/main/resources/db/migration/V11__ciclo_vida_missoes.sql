-- =============================================================================
-- V11 — Ciclo de vida de missões (F4)
--
-- Renomeia CRIADA→RASCUNHO e DISPONIVEL→ABERTA, introduz AGUARDANDO_CONFIRMACAO
-- e EM_DISPUTA, e amplia as colunas de status.
--
-- Os UPDATE do passo (3) são hoje VESTIGIAIS em dev/test, e isso é deliberado.
-- Quando esta migration foi escrita, o seed era V9 e rodava ANTES dela, com
-- status 'DISPONIVEL'; os UPDATE existiam para reescrever aquelas linhas. O seed
-- passou a ser V900 (ver o cabeçalho dele) e agora roda DEPOIS, já gravando
-- 'ABERTA' — não há mais linha a renomear, aqui ou em produção, onde o seed nunca
-- rodou. Mantidos porque continuam corretos e cobrem qualquer banco de
-- desenvolvimento anterior a essa mudança; remover só traria risco, sem ganho.
--
-- ORDEM OBRIGATÓRIA: (1) derrubar o CHECK antigo → (2) alargar as colunas →
-- (3) reescrever os dados → (4) recriar o CHECK. A ordem importa para bancos
-- legados: criar o CHECK novo antes do UPDATE faria linhas 'DISPONIVEL'
-- preexistentes violarem a constraint.
-- =============================================================================

-- (1) V3 declarou os CHECK no nível da coluna, sem nome explícito; o PostgreSQL
--     os nomeia <tabela>_<coluna>_check. IF EXISTS evita falha em bancos onde o
--     nome tenha divergido — se algum sobrar, o UPDATE do passo (3) falha alto,
--     que é o comportamento desejado: nunca silencioso.
ALTER TABLE missao        DROP CONSTRAINT IF EXISTS missao_status_check;
ALTER TABLE missao_evento DROP CONSTRAINT IF EXISTS missao_evento_tipo_check;

-- (2) VARCHAR(30): 'AGUARDANDO_CONFIRMACAO' tem 22 caracteres e não cabe em 15.
--     Folga também para 'CHECK_IN_REGISTRADO' (19), o maior tipo de evento.
ALTER TABLE missao        ALTER COLUMN status      TYPE VARCHAR(30);
ALTER TABLE missao_evento ALTER COLUMN de_status   TYPE VARCHAR(30);
ALTER TABLE missao_evento ALTER COLUMN para_status TYPE VARCHAR(30);

-- (3) Renomeações. missao_evento está vazio no seed, mas os UPDATE cobrem bancos
--     de desenvolvimento onde eventos já tenham sido gerados manualmente.
UPDATE missao        SET status      = 'RASCUNHO' WHERE status      = 'CRIADA';
UPDATE missao        SET status      = 'ABERTA'   WHERE status      = 'DISPONIVEL';
UPDATE missao_evento SET de_status   = 'RASCUNHO' WHERE de_status   = 'CRIADA';
UPDATE missao_evento SET de_status   = 'ABERTA'   WHERE de_status   = 'DISPONIVEL';
UPDATE missao_evento SET para_status = 'RASCUNHO' WHERE para_status = 'CRIADA';
UPDATE missao_evento SET para_status = 'ABERTA'   WHERE para_status = 'DISPONIVEL';

-- (4) Conjuntos novos, agora com nome explícito para que a próxima migration não
--     dependa da convenção de nomes do PostgreSQL.
ALTER TABLE missao ADD CONSTRAINT ck_missao_status CHECK (status IN (
    'RASCUNHO', 'ABERTA', 'ACEITA', 'EM_ANDAMENTO',
    'AGUARDANDO_CONFIRMACAO', 'EM_DISPUTA',
    'CONCLUIDA', 'CANCELADA', 'EXPIRADA'
));

ALTER TABLE missao_evento ADD CONSTRAINT ck_missao_evento_tipo CHECK (tipo IN (
    'PUBLICADA', 'ACEITA', 'DESISTIDA', 'INICIADA',
    'CHECK_IN_REGISTRADO', 'CHECK_IN_REJEITADO',
    'CONFIRMADA', 'CONTESTADA', 'DISPUTA_RESOLVIDA',
    'CONCLUIDA', 'CANCELADA', 'EXPIRADA'
));

-- Índice parcial para o job de expiração: o predicado do job é exatamente
-- (status = 'ABERTA' AND janela_fim < now()). Sendo parcial, é uma fração da
-- tabela e evita varrer missões já concluídas ou canceladas a cada execução.
CREATE INDEX idx_missao_expiravel ON missao (janela_fim) WHERE status = 'ABERTA';

-- Índice composto para a listagem paginada: filtro por status + ordenação padrão
-- por criada_em DESC. Sem ele, cada página faz sort em memória.
CREATE INDEX idx_missao_status_criada ON missao (status, criada_em DESC);

-- Sem GRANT novo: V11 não cria tabela, e privilégio no PostgreSQL é por tabela —
-- os GRANT/REVOKE de V3 sobrevivem ao ALTER COLUMN TYPE. missao_evento continua
-- append-only para o role omnitribo_app.
