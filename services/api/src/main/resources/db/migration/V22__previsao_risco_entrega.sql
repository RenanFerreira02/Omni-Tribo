-- V22 — Previsão de risco de falha de entrega
--
-- Duas mudanças independentes, na mesma migration porque nascem da mesma feature:
--
-- (1) entrega_falida passa a GRAVAR o que o modelo disse, e as características que ele viu.
-- (2) alerta ganha prioridade, para o fan-out não tratar toda missão como igual.
--
-- Todas as colunas são NULÁVEIS: as linhas que já existem — inclusive as dos seeds V901, V903 e
-- V904 — foram criadas antes de o modelo existir e não têm como ser preenchidas retroativamente.
-- Fingir um valor para elas seria inventar histórico.

-- ─────────────────────────── (1) O que o modelo previu, e com o quê ───────────────────────────
--
-- GRAVAR A PREVISÃO É PARTE DA HONESTIDADE DO MODELO, não telemetria.
--
-- Os coeficientes vieram de dados SINTÉTICOS (ver ADR 0022 e docs/qualidade/modelo-previsao.md), e o
-- próximo passo declarado é validar contra a operação real. Essa validação só é possível se
-- existirem previsões registradas para comparar com desfechos observados. Sem estas colunas, cada
-- score seria calculado, usado para creditar token, e descartado — e daqui a seis meses não haveria
-- absolutamente nada contra o que medir se o modelo acertou.
--
-- risco_versao_modelo é o par de versao_formula: responde "este multiplicador estava certo quando
-- foi aplicado?" depois de o modelo ter sido re-treinado.

ALTER TABLE entrega_falida ADD COLUMN risco_probabilidade NUMERIC(5,4);
ALTER TABLE entrega_falida ADD CONSTRAINT ck_entrega_falida_risco_probabilidade
    CHECK (risco_probabilidade IS NULL OR (risco_probabilidade >= 0 AND risco_probabilidade <= 1));

ALTER TABLE entrega_falida ADD COLUMN risco_faixa VARCHAR(5);
ALTER TABLE entrega_falida ADD CONSTRAINT ck_entrega_falida_risco_faixa
    CHECK (risco_faixa IS NULL OR risco_faixa IN ('BAIXO', 'MEDIO', 'ALTO'));

-- NUMERIC(4,2) casa com missao.multiplicador_risco (V16). O teto de 1,50 é calibração e vive no
-- application.yml; o CHECK aqui só barra valor absurdo — um multiplicador abaixo de 1 inverteria a
-- tese do produto (risco REDUZINDO recompensa) e nenhuma calibração deveria produzi-lo.
ALTER TABLE entrega_falida ADD COLUMN risco_multiplicador NUMERIC(4,2);
ALTER TABLE entrega_falida ADD CONSTRAINT ck_entrega_falida_risco_multiplicador
    CHECK (risco_multiplicador IS NULL OR risco_multiplicador >= 1.00);

ALTER TABLE entrega_falida ADD COLUMN risco_versao_modelo INT;

-- ─────────────────────── As características que a transportadora informa ───────────────────────
--
-- Três das oito características do modelo não existiam no webhook: janela horária pretendida, tipo
-- de endereço e tentativas anteriores. As outras cinco já são deriváveis do que a linha guarda
-- (peso, volume, CEP) ou vêm do provedor de clima.
--
-- Opcionais no webhook e nuláveis aqui de propósito: transportadora já integrada continua enviando o
-- corpo antigo sem quebrar, e o que faltar é imputado — hora e dia da semana caem para o derivado de
-- recebido_em, clima para a média do treino.

ALTER TABLE entrega_falida ADD COLUMN janela_hora_inicio SMALLINT;
ALTER TABLE entrega_falida ADD CONSTRAINT ck_entrega_falida_janela_hora
    CHECK (janela_hora_inicio IS NULL OR (janela_hora_inicio >= 0 AND janela_hora_inicio <= 23));

ALTER TABLE entrega_falida ADD COLUMN tipo_endereco VARCHAR(12);
ALTER TABLE entrega_falida ADD CONSTRAINT ck_entrega_falida_tipo_endereco
    CHECK (tipo_endereco IS NULL
           OR tipo_endereco IN ('RESIDENCIAL', 'COMERCIAL', 'CONDOMINIO', 'RURAL'));

ALTER TABLE entrega_falida ADD COLUMN tentativas_anteriores SMALLINT;
ALTER TABLE entrega_falida ADD CONSTRAINT ck_entrega_falida_tentativas
    CHECK (tentativas_anteriores IS NULL OR tentativas_anteriores >= 0);

COMMENT ON COLUMN entrega_falida.risco_probabilidade IS
    'Probabilidade de falha estimada na conversão. Congelada: o modelo pode ser re-treinado depois.';
COMMENT ON COLUMN entrega_falida.risco_versao_modelo IS
    'Versão de app.logistica.risco vigente quando o score foi calculado.';

-- ─────────────────── (1b) A faixa também na missão, para o aviso no detalhe ───────────────────
--
-- missao.multiplicador_risco já existe desde a V16, reservada. Falta a FAIXA, e ela não é derivável
-- do multiplicador de forma estável: a função probabilidade→multiplicador é calibração, e uma
-- recalibração futura mudaria a inversa sem mudar a coluna. Guardar a faixa congelada é o que faz o
-- aviso exibido continuar coerente com o que foi decidido na criação.
--
-- Nulável: toda missão que não veio de entrega falida não tem risco avaliado, e a maioria nunca terá.

ALTER TABLE missao ADD COLUMN faixa_risco VARCHAR(5);
ALTER TABLE missao ADD CONSTRAINT ck_missao_faixa_risco
    CHECK (faixa_risco IS NULL OR faixa_risco IN ('BAIXO', 'MEDIO', 'ALTO'));

COMMENT ON COLUMN missao.faixa_risco IS
    'Congelada na criação, junto de multiplicador_risco e versao_formula. Nula fora do webhook.';

-- ───────────────────────── (2) Prioridade na caixa de entrada ─────────────────────────
--
-- NOT NULL DEFAULT 0, ao contrário das colunas acima: prioridade não é um dado histórico que falta,
-- é uma classificação com um padrão natural. Todo alerta já existente é, corretamente, de prioridade
-- normal — nenhum deles nasceu de uma avaliação de risco.
--
-- 0 = normal, 1 = risco MEDIO, 2 = risco ALTO. SMALLINT e não varchar+CHECK como os outros enums do
-- projeto: aqui o valor é ORDENÁVEL e a ordenação é o uso principal da coluna. 'ALTO' < 'BAIXO' <
-- 'MEDIO' em ordem alfabética, que é exatamente o contrário do pretendido.

ALTER TABLE alerta ADD COLUMN prioridade SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE alerta ADD CONSTRAINT ck_alerta_prioridade
    CHECK (prioridade >= 0 AND prioridade <= 2);

-- Substitui idx_alerta_usuario_recente (V21) como índice da caixa de entrada: a consulta passa a
-- ordenar por prioridade e depois por data. O antigo permanece porque o teto por hora ainda conta
-- alertas por (usuario_id, criado_em) sem olhar prioridade.
CREATE INDEX idx_alerta_usuario_prioridade
    ON alerta (usuario_id, prioridade DESC, criado_em DESC);

COMMENT ON COLUMN alerta.prioridade IS
    '0 normal, 1 risco MEDIO, 2 risco ALTO. Ordenável de propósito — ver DespachanteAlertaService.';
