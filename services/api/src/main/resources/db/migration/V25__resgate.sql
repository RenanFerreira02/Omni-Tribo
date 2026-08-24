-- =============================================================================
-- V25 — Resgate: a linha que registra a queima
--
-- Um resgate é o ÚNICO caminho pelo qual token sai da economia. O lançamento
-- correspondente (motivo RESGATE, V26) debita e NÃO credita ninguém — não há
-- contraparte, não há missao_id. É isso que faz dele sumidouro e não transferência.
-- Ver ADR 0027.
-- =============================================================================

CREATE TABLE resgate (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- FK para usuario — módulo→identidade é permitido.
    usuario_id      UUID        NOT NULL REFERENCES usuario(id),
    beneficio_id    UUID        NOT NULL REFERENCES beneficio(id),

    -- O custo COBRADO, congelado nesta linha.
    --
    -- Não é redundante com beneficio.custo_tokens: aquele é o preço VIGENTE e pode
    -- mudar amanhã. Sem congelar aqui, um reajuste reinterpretaria retroativamente
    -- todo resgate já feito, e some a resposta para "quanto esta pessoa pagou por
    -- isto, no dia em que pagou?". Mesmo raciocínio de missao.versao_formula.
    custo_tokens    BIGINT      NOT NULL CHECK (custo_tokens > 0),

    -- Código que a pessoa mostra no balcão.
    --
    -- NÃO É SEGREDO CRIPTOGRÁFICO, e o schema não finge que é: são 8 caracteres de
    -- um alfabeto sem ambiguidade visual, para um humano ler em voz alta e outro
    -- digitar. Quem autoriza a baixa é um ADMIN, pelo id do resgate; o código serve
    -- para casar o papel com a linha. Não há HMAC nem assinatura aqui de propósito
    -- — inventar criptografia para um identificador de balcão daria a ele uma
    -- aparência de credencial que ele não tem, e alguém acabaria confiando nisso.
    --
    -- UNIQUE mesmo assim: dois resgates com o mesmo código não seriam inseguros,
    -- seriam confusos no balcão.
    codigo_retirada VARCHAR(8)  NOT NULL,

    status          VARCHAR(10) NOT NULL CHECK (status IN ('PENDENTE', 'UTILIZADO')),
    criado_em       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    utilizado_em    TIMESTAMPTZ,

    CONSTRAINT uk_resgate_codigo UNIQUE (codigo_retirada)
);

-- Paridade entre status e carimbo, no molde de ck_entrega_falida_recusa_coerente
-- (V23): um UTILIZADO sem instante não diz quando, e um instante sem UTILIZADO é
-- uma linha que se contradiz. As duas metades sempre juntas.
ALTER TABLE resgate ADD CONSTRAINT ck_resgate_utilizado_coerente
    CHECK ((status = 'UTILIZADO') = (utilizado_em IS NOT NULL));

-- "Meus resgates", ordenado do mais recente. O filtro por usuário sozinho deixaria
-- a ordenação para sort em memória — mesmo raciocínio de
-- idx_lancamento_carteira_criado (V13).
CREATE INDEX idx_resgate_usuario_criado ON resgate (usuario_id, criado_em DESC);

-- Fila do parceiro: o que ainda não foi retirado. Parcial porque UTILIZADO é
-- terminal e acumula para sempre, enquanto PENDENTE é o conjunto pequeno e quente.
CREATE INDEX idx_resgate_pendente ON resgate (beneficio_id) WHERE status = 'PENDENTE';

-- UPDATE é necessário: a baixa do parceiro muda status e utilizado_em. DELETE não —
-- resgate é registro de uma queima, e apagá-lo deixaria o lançamento do ledger
-- apontando para nada.
GRANT SELECT, INSERT, UPDATE ON resgate TO omnitribo_app;
REVOKE DELETE ON resgate FROM omnitribo_app;
