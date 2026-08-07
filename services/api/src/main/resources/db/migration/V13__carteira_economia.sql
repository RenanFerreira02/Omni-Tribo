-- =============================================================================
-- V13 — Carteira e Economia (F5)
--
-- Fecha o schema que faltava para o ledger sustentar a afirmação de ACID: pote de
-- financiamento na missão, barreiras de integridade que faltavam em V5, e os
-- índices dos dois caminhos de leitura novos (extrato e teto por janela).
--
-- Por que V13 e não V12: a branch feat/f6-geolocalizacao já ocupa a V12 com
-- V12__checkin_idempotencia.sql. Versão de Flyway é sequência GLOBAL — duas
-- migrations com versão 12 derrubariam o merge das duas fases com
-- "more than one migration with version 12", num erro que não aponta para
-- nenhuma das duas branches.
--
-- Sem GRANT novo. Privilégio no PostgreSQL é por TABELA e sobrevive ao ALTER
-- TABLE — mesma constatação já registrada no rodapé da V11. Em particular
-- `lancamento` continua com GRANT SELECT, INSERT e REVOKE UPDATE, DELETE para
-- omnitribo_app: nada nesta fase precisa de UPDATE nele, e nada deve. Acrescentar
-- a coluna `mensagem` não muda isso, porque ela só é escrita no INSERT original.
-- =============================================================================

-- -----------------------------------------------------------------------
-- (1) Pote de financiamento da missão
-- -----------------------------------------------------------------------
-- Financiar DEBITA a carteira do membro e CREDITA aqui; concluir uma missão
-- TRIBO/COLETA PAGA daqui. Nenhum token é emitido nesse ciclo — o pote é custódia
-- dentro do sistema, e a soma (carteiras + potes) é conservada. Sem isso, concluir
-- missão cunharia token do nada e a economia inflaria indefinidamente: é o que a
-- ausência de sumidouro significa na prática.
ALTER TABLE missao ADD COLUMN pote_tokens BIGINT NOT NULL DEFAULT 0;

-- Barreira, não validação primária. A recusa amigável (422) vem do serviço, sob o
-- lock da linha. Esta constraint existe para que um bug de lógica vire falha alta
-- em vez de pote negativo silencioso — mesmo papel que uk_lancamento_idempotencia
-- cumpre para o crédito duplicado.
ALTER TABLE missao ADD CONSTRAINT ck_missao_pote_nao_negativo CHECK (pote_tokens >= 0);

-- -----------------------------------------------------------------------
-- (2) Integridade que faltava em V5
-- -----------------------------------------------------------------------
-- V5 criou carteira e lancamento sem nenhuma restrição de sinal ou de faixa.
-- Carteira.debitar aceitava dirigir o saldo a negativo, e nada no banco impedia.
ALTER TABLE carteira ADD CONSTRAINT ck_carteira_saldo_nao_negativo
    CHECK (saldo_brl >= 0 AND saldo_tokens >= 0);

-- O SINAL mora na coluna `sinal`, nunca no valor. Um valor_brl negativo numa linha
-- DEBITO significaria, na conta do ledger
-- (SUM(CASE sinal WHEN 'CREDITO' THEN v ELSE -v END)), um CRÉDITO — e toda
-- reconciliação passaria a mentir sem produzir erro nenhum. É a classe de bug que
-- a reconciliação existe para pegar, então ela não pode ser a única defesa.
ALTER TABLE lancamento ADD CONSTRAINT ck_lancamento_valores_nao_negativos
    CHECK (valor_brl >= 0 AND valor_tokens >= 0);

-- Lançamento de valor zero em ambas as moedas consumiria uma chave de idempotência
-- sem mover nada — um replay silencioso que o cliente leria como sucesso.
ALTER TABLE lancamento ADD CONSTRAINT ck_lancamento_valor_nao_nulo
    CHECK (valor_brl > 0 OR valor_tokens > 0);

-- -----------------------------------------------------------------------
-- (3) Mensagem opcional da transferência P2P
-- -----------------------------------------------------------------------
-- Fato do lançamento, imutável como o resto da linha: quem transferiu escreveu
-- isto no momento da transferência, e corrigir depois seria reescrever história.
ALTER TABLE lancamento ADD COLUMN mensagem VARCHAR(200);

-- -----------------------------------------------------------------------
-- (4) Índices dos caminhos de leitura novos
-- -----------------------------------------------------------------------
-- Extrato: GET /api/v1/carteira/lancamentos filtra por carteira e ordena por
-- criado_em DESC. idx_lancamento_carteira (V8) resolve só o filtro e deixa a
-- ordenação para sort em memória — mesmo raciocínio de idx_missao_status_criada
-- na V11.
CREATE INDEX idx_lancamento_carteira_criado ON lancamento (carteira_id, criado_em DESC);

-- Redundante a partir daqui: é prefixo à esquerda do composto acima, e o planner
-- nunca o escolheria. Removido, e não mantido "por segurança", porque `lancamento`
-- é a tabela de INSERT mais quente do sistema — todo índice extra é amplificação
-- de escrita exatamente no caminho que os testes de concorrência estressam.
DROP INDEX idx_lancamento_carteira;

-- Teto por janela: SUM(valor_tokens) das transferências ENVIADAS de uma carteira
-- desde um instante. Parcial de propósito — transferência é fração pequena do
-- ledger, e o índice fica pequeno e quente em vez de duplicar a tabela inteira.
CREATE INDEX idx_lancamento_transferencia_janela
    ON lancamento (carteira_id, criado_em DESC)
    WHERE motivo = 'TRANSFERENCIA_ENVIADA';
