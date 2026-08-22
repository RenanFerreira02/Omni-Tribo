-- =============================================================================
-- V24 — Parceiro e benefício: o catálogo do sumidouro
--
-- O ADR 0009 §3 decidiu que o resgate em benefício de parceiro é o SUMIDOURO do
-- TOKEN, e não atribuiu a decisão a nenhuma fase. Resultado: até aqui o backend
-- não tinha uma linha sobre isso — `grep` por resgate/cupom/beneficio/parceiro em
-- services/api, db/migration e db/seed devolvia só comentário, e a tela de
-- benefícios do app era vitrine servida por um catálogo hardcoded.
--
-- Sem sumidouro a economia só tem entrada: o aporte do patrocinador emite (V23),
-- as missões movem, e nada nunca sai. Esta migration e as duas seguintes fecham a
-- conservação como CICLO em vez de ESTOQUE. Ver ADR 0027.
--
-- POR QUE V24 E NÃO V25: o schema ia até a V23 e a V24 estava livre. Reservar a
-- partir da V25 deixaria um buraco, e buraco de versão é bomba-relógio — um V24
-- criado depois por qualquer branch fica out-of-order para todo banco que já
-- aplicou V25+, e application-dev.yml mantém out-of-order DESLIGADO. É a mesma
-- classe de armadilha que queimou a V9 e a V10.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. O parceiro do bairro.
--
-- Mesmo molde de ponto_custodia (V6): ponto GEOGRAPHY(POINT,4326), endereço em
-- colunas, `ativo` para desligar sem apagar. Desligar em vez de apagar importa
-- aqui: um parceiro que saiu do programa continua tendo resgates apontando para os
-- benefícios dele, e apagar a linha deixaria o extrato de quem resgatou sem
-- explicação.
--
-- DISTÂNCIA NÃO É COLUNA. Ela é derivada por ST_Distance sobre geography, em
-- metros, dentro de ConsultasGeoespaciais — a única classe do projeto autorizada a
-- escrever ST_*. Guardar distância pré-calculada seria guardar uma resposta que
-- depende de quem pergunta e de onde.
-- -----------------------------------------------------------------------------
CREATE TABLE parceiro (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    nome        VARCHAR(100) NOT NULL,
    ponto       GEOGRAPHY(POINT,4326) NOT NULL,
    -- tribo_id: FK para tribo — módulo→identidade é permitido, como em ponto_custodia.
    -- Nulável: um parceiro pode atender mais de um bairro, e nesse caso só a
    -- proximidade o encontra.
    tribo_id    UUID         REFERENCES tribo(id),
    cep         VARCHAR(8)   NOT NULL,
    logradouro  VARCHAR(200) NOT NULL,
    bairro      VARCHAR(100) NOT NULL,
    cidade      VARCHAR(100) NOT NULL,
    uf          VARCHAR(2)   NOT NULL,
    ativo       BOOLEAN      NOT NULL DEFAULT TRUE,
    criado_em   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- GiST, como idx_ponto_custodia_ponto (V8). Sem ele, ST_DWithin vira Seq Scan na
-- tabela inteira a cada abertura da tela de benefícios — e a evidência de que o
-- índice é de fato usado está em docs/evidencias/f6-explain-analyze.md.
CREATE INDEX idx_parceiro_ponto ON parceiro USING GIST (ponto);

-- Catálogo por tribo: o segundo filtro do GET /beneficios. Parcial, porque só
-- parceiro ativo aparece e a maioria das linhas será ativa — o índice fica pequeno.
CREATE INDEX idx_parceiro_tribo ON parceiro (tribo_id) WHERE ativo;

GRANT SELECT, INSERT, UPDATE ON parceiro TO omnitribo_app;

-- -----------------------------------------------------------------------------
-- 2. O benefício.
--
-- `custo_tokens` é o preço VIGENTE; o resgate congela o que foi cobrado na própria
-- linha de resgate (V25). Sem isso, mudar o preço reinterpretaria retroativamente
-- todo resgate já feito — mesmo raciocínio de versao_formula em missao.
-- -----------------------------------------------------------------------------
CREATE TABLE beneficio (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    parceiro_id  UUID         NOT NULL REFERENCES parceiro(id),
    titulo       VARCHAR(120) NOT NULL,
    descricao    VARCHAR(500) NOT NULL,
    custo_tokens BIGINT       NOT NULL,
    -- BEM: um objeto ou serviço ("um café coado", "uma aula experimental").
    -- PERCENTUAL: um desconto proporcional ("20% na segunda via").
    tipo         VARCHAR(10)  NOT NULL CHECK (tipo IN ('BEM', 'PERCENTUAL')),
    ativo        BOOLEAN      NOT NULL DEFAULT TRUE,
    criado_em    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Custo zero consumiria uma chave de idempotência sem queimar nada, e o lançamento
-- correspondente bateria em ck_lancamento_valor_nao_nulo (V13). Barreira, não
-- validação primária: a recusa amigável vem do serviço.
ALTER TABLE beneficio ADD CONSTRAINT ck_beneficio_custo_positivo CHECK (custo_tokens > 0);

-- -----------------------------------------------------------------------------
-- 3. Benefício NUNCA se expressa em reais.
--
-- É a regra do ADR 0009 §6 virando barreira de banco. O motivo não é estético:
-- token conversível em moeda corrente É dinheiro, com KYC e enquadramento
-- regulatório junto, e o projeto declarou isso fora de escopo. Um benefício
-- anunciado como "R$ 10 de desconto" publica uma cotação implícita — quem lê
-- descobre quantos tokens valem dez reais, e a partir daí o preço de todo benefício
-- do catálogo é uma tabela de câmbio.
--
-- O app já reprova isso em dois pontos (src/features/beneficios/catalogo.ts, no
-- tipo e no javadoc; e __tests__/catalogo.test.ts, com a regex /R\$|\breais\b/i).
-- Faltava o servidor. Aqui é a barreira FINAL: a recusa amigável, com 400 e o campo
-- apontado, vem da Bean Validation em CadastrarBeneficioRequest.
--
-- \y é fronteira de palavra no POSIX do PostgreSQL (o equivalente ao \b do PCRE):
-- sem ela, "real" casaria dentro de "realmente" e o CHECK reprovaria texto legítimo.
-- -----------------------------------------------------------------------------
ALTER TABLE beneficio ADD CONSTRAINT ck_beneficio_sem_reais
    CHECK (titulo !~* 'R\$|\yreais?\y' AND descricao !~* 'R\$|\yreais?\y');

COMMENT ON CONSTRAINT ck_beneficio_sem_reais ON beneficio IS
  'ADR 0009 §6: benefício se expressa em BEM ou PERCENTUAL, nunca em reais. Preço em moeda corrente '
  'publica uma cotação token→real implícita, e token conversível é dinheiro.';

CREATE INDEX idx_beneficio_parceiro ON beneficio (parceiro_id) WHERE ativo;

GRANT SELECT, INSERT, UPDATE ON beneficio TO omnitribo_app;
