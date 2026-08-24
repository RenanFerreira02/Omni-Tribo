-- =============================================================================
-- V23 — Carteira de patrocinador
--
-- Fecha a Pendência #1: ENTREGA deixa de CUNHAR token na conclusão e passa a
-- pagar de um pote financiado pelo PATROCINADOR, pela mecânica que já existe em
-- FinanciamentoMissao. O ADR 0024 registra as três decisões desta migration.
--
-- A cunhagem NÃO desaparece — ela muda de lugar, e essa é a tese da mudança.
-- Hoje ela é implícita, por missão, dentro da conclusão, e a reconciliação não a
-- enxerga (ledger e projeção seguem batendo). Depois desta migration o único
-- ponto de emissão é APORTE_PATROCINADOR, que é endpoint ADMIN, auditado e
-- idempotente. A invariante passa a ser enunciável:
--
--   SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens) é constante em todo o
--   ciclo de missões. Só o aporte a altera.
--
-- AJUDA continua cunhando. Não há financiador para ela e não se inventou um:
-- fica declarado em missao.fonte_pote = 'CUNHAGEM', que é justamente o ponto de
-- ter a coluna — o que antes era regra implícita num método privado agora é dado
-- na linha da missão, auditável junto com a missão que o aplicou.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. O papel PATROCINADOR.
--
-- O ALTER COLUMN vem ANTES do CHECK e é obrigatório: a coluna é VARCHAR(10)
-- (V2:25) e 'PATROCINADOR' tem 12 caracteres. Sem ele o INSERT do patrocinador
-- falharia com "value too long for type character varying(10)" — um erro que não
-- menciona papel nenhum e some no meio de um cadastro que parece correto.
--
-- O CHECK da V2 é de COLUNA e anônimo, então o PostgreSQL o nomeou
-- usuario_papel_check. Verificado no banco (pg_constraint) antes de escrever esta
-- linha, e não deduzido: derrubar um nome que não existe aborta a migration
-- inteira, e o nome gerado é convenção, não contrato.
-- -----------------------------------------------------------------------------
ALTER TABLE usuario ALTER COLUMN papel TYPE VARCHAR(20);

ALTER TABLE usuario DROP CONSTRAINT usuario_papel_check;

ALTER TABLE usuario ADD CONSTRAINT ck_usuario_papel
    CHECK (papel IN ('USUARIO', 'ADMIN', 'PATROCINADOR'));

COMMENT ON COLUMN usuario.papel IS
  'USUARIO, ADMIN ou PATROCINADOR. O patrocinador é titular de carteira e financiador de pote; '
  'nunca autentica (status INATIVO, como o usuário-sistema da V21).';

-- -----------------------------------------------------------------------------
-- 2. Quem é o patrocinador de cada transportadora.
--
-- Esta tabela preenche uma lacuna real: hoje NADA no sistema liga o slug que a
-- transportadora manda no cabeçalho X-Transportadora a um titular de carteira. O
-- slug só existe como chave do mapa de segredos em configuração
-- (ParametrosWebhook), e segredo continua lá — ver o javadoc daquele record sobre
-- por que segredo não vira tabela. O que entra aqui é a RELAÇÃO COMERCIAL, que
-- não é segredo e precisa de FK, de unicidade e de auditoria.
--
-- usuario_id UNIQUE: um patrocinador é exatamente um titular, e a carteira dele é
-- alcançada por uk_carteira_usuario (V5:14) sem nenhuma mudança no módulo
-- carteira. Foi essa propriedade que fez a opção "patrocinador é uma linha em
-- usuario" ser escolhida em vez de titular polimórfico — ver ADR 0024 §4.
--
-- transportadora_slug UNIQUE: a resolução no webhook é slug → patrocinador, e
-- duas linhas para o mesmo slug tornariam o resultado não determinístico
-- exatamente no caminho que decide se uma missão nasce ou não.
-- -----------------------------------------------------------------------------
CREATE TABLE patrocinador (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- FK para usuario — módulo→identidade é permitido, e aqui a tabela é do
    -- próprio identidade, que é dono de usuario.
    usuario_id          UUID         NOT NULL REFERENCES usuario(id),
    transportadora_slug VARCHAR(50)  NOT NULL,
    nome                VARCHAR(100) NOT NULL,
    -- Desligar um patrocínio não apaga a linha: os lançamentos dele continuam no
    -- ledger apontando para a carteira, e apagar a relação deixaria o extrato sem
    -- explicação. Inativo faz o webhook cair no desfecho SEM_PATROCINIO.
    ativo               BOOLEAN      NOT NULL DEFAULT TRUE,
    criado_em           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_patrocinador_usuario UNIQUE (usuario_id),
    CONSTRAINT uk_patrocinador_slug    UNIQUE (transportadora_slug)
);

COMMENT ON TABLE patrocinador IS
  'Relação comercial transportadora → titular de carteira que financia o pote das missões de '
  'retirada. O SEGREDO HMAC daquela transportadora NÃO mora aqui: fica em app.webhooks.segredos.';

GRANT SELECT, INSERT, UPDATE ON patrocinador TO omnitribo_app;

-- -----------------------------------------------------------------------------
-- 3. Os dois motivos novos do ledger.
--
-- FINANCIAMENTO_PATROCINADOR (26 chars) e APORTE_PATROCINADOR (19) cabem no
-- VARCHAR(30) da V5 — conferido, porque um truncamento aqui gravaria motivo
-- inválido no ledger append-only, onde correção só existe por estorno.
--
-- POR QUE UM MOTIVO NOVO E NÃO REUSO DE FINANCIAMENTO_TRIBO: o extrato e a
-- exportação LGPD mostram o motivo cru, e "FINANCIAMENTO_TRIBO" numa carteira de
-- patrocinador afirmaria um pertencimento que não existe. Cada motivo do enum
-- corresponde a exatamente um call site — é a doutrina de MotivoLancamento.
--
-- CONSEQUÊNCIA QUE NÃO PODE SER ESQUECIDA, e que esta migration sozinha não
-- resolve: LancamentoRepository.buscarFinanciamentosDaMissao filtra
-- motivo = FINANCIAMENTO_TRIBO. Sem alargar aquela query para os DOIS motivos, o
-- estorno de missão cancelada/expirada não devolveria nada ao patrocinador, os
-- tokens ficariam presos e a reconciliação seguiria respondendo integro=true —
-- a Pendência #5 reaparecendo por outro caminho.
-- -----------------------------------------------------------------------------
ALTER TABLE lancamento DROP CONSTRAINT lancamento_motivo_check;

ALTER TABLE lancamento ADD CONSTRAINT ck_lancamento_motivo CHECK (motivo IN (
    'RECOMPENSA_MISSAO', 'TRANSFERENCIA_ENVIADA', 'TRANSFERENCIA_RECEBIDA',
    'FINANCIAMENTO_TRIBO', 'FINANCIAMENTO_PATROCINADOR', 'APORTE_PATROCINADOR',
    'SAQUE', 'BONUS', 'ESTORNO'
));

COMMENT ON CONSTRAINT ck_lancamento_motivo ON lancamento IS
  'APORTE_PATROCINADOR é o ÚNICO motivo que emite token sem débito de contraparte. Acrescentar '
  'outro exige sumidouro correspondente, senão a conservação vira função de quantas campanhas '
  'rodaram — ver o javadoc de MotivoLancamento.BONUS.';

-- -----------------------------------------------------------------------------
-- 4. De onde sai o token que a missão paga.
--
-- Substitui a decisão por CATEGORIA que vivia em MissaoService.pagaTokensDoPote.
-- Virar aquela chave para "ENTREGA inteira" quebraria a ENTREGA criada por
-- humano: ela ficaria impublicável, porque FinanciamentoService.validarEstado
-- recusa financiamento de ENTREGA e o pote nunca chegaria à recompensa. A coluna
-- separa as duas ENTREGAs — a do webhook, patrocinada, e a de humano, que segue
-- cunhando.
--
-- Congelada na criação, como versao_formula e multiplicador_risco, e pela mesma
-- razão: recalibrar a regra depois não pode reescrever o passado nem tornar
-- inexplicável um crédito já feito.
--
-- DEFAULT 'CUNHAGEM' é o valor CONSERVADOR: preserva exatamente o comportamento
-- de hoje para qualquer linha que esta migration não enxergue. O backfill logo
-- abaixo corrige as que já existem.
--
-- ARMADILHA DE SEED, e é a mesma que a V21:18-28 documentou. As migrations 900+
-- rodam DEPOIS desta e inserem missões com lista de colunas explícita, que não
-- pode ser editada sem quebrar o checksum de todo banco de dev existente. Toda
-- missão TRIBO/COLETA semeada pegaria o DEFAULT 'CUNHAGEM', a conclusão passaria
-- a cunhar e o pote financiado ficaria preso. Por isso o backfill é feito DUAS
-- vezes: aqui, para bancos com dados reais, e de novo na V905, que roda depois
-- dos seeds. Inverter o DEFAULT para 'COMUNIDADE' seria pior — quebraria as
-- ENTREGA e AJUDA semeadas com 422 na conclusão, por pote inexistente.
--
-- NÃO existe CHECK de coerência entre fonte_pote e categoria, e a ausência é
-- deliberada: ele reprovaria os INSERTs dos próprios seeds antes de a V905 poder
-- corrigi-los. A coerência mora no construtor de Missao, que é ponto único —
-- mesma escolha que a V21 fez para nivel_minimo.
-- -----------------------------------------------------------------------------
ALTER TABLE missao ADD COLUMN fonte_pote VARCHAR(12) NOT NULL DEFAULT 'CUNHAGEM';

ALTER TABLE missao ADD CONSTRAINT ck_missao_fonte_pote
    CHECK (fonte_pote IN ('COMUNIDADE', 'PATROCINADOR', 'CUNHAGEM'));

COMMENT ON COLUMN missao.fonte_pote IS
  'De onde sai o token da recompensa: COMUNIDADE (pote financiado por membros da tribo), '
  'PATROCINADOR (pote financiado pelo patrocinador da transportadora) ou CUNHAGEM (emitido na '
  'conclusão — só AJUDA e ENTREGA criada por humano). Congelada na criação.';

UPDATE missao SET fonte_pote = 'COMUNIDADE' WHERE categoria IN ('TRIBO', 'COLETA');

-- -----------------------------------------------------------------------------
-- 5. Por que uma encomenda foi recusada.
--
-- A recusa por falta de patrocínio é o MESMO fato operacional que a recusa por
-- lotação: a encomenda não entrou na custódia, não gerou missão e não ocupa vaga.
-- Por isso ela REUSA recusada_em em vez de ganhar coluna própria, e a decisão tem
-- consequência medida: a invariante de ocupação que MigracaoTest trava filtra por
-- "ef.recusada_em IS NULL", e ck_entrega_falida_recusada_sem_missao (V21) já
-- garante que recusada não tem missão. Uma coluna paralela obrigaria a alterar as
-- duas coisas e criaria um terceiro estado que nenhuma delas conhece.
--
-- O que muda é só a EXPLICAÇÃO, que a transportadora precisa ler para saber se
-- reenviar adianta: ponto lotado pode abrir vaga; falta de patrocínio, não.
--
-- Nulável, e a paridade com recusada_em entra validada sem backfill: verifiquei
-- que NENHUM seed grava recusada_em (V901, V903 e V904 omitem a coluna), então
-- ambos os lados são NULL em toda linha existente.
-- -----------------------------------------------------------------------------
ALTER TABLE entrega_falida ADD COLUMN motivo_recusa VARCHAR(20);

ALTER TABLE entrega_falida ADD CONSTRAINT ck_entrega_falida_motivo_recusa
    CHECK (motivo_recusa IS NULL OR motivo_recusa IN ('PONTO_LOTADO', 'SEM_PATROCINIO'));

-- Paridade: uma recusa sem motivo é registro que não explica nada, e um motivo
-- sem recusa é linha que se contradiz.
ALTER TABLE entrega_falida ADD CONSTRAINT ck_entrega_falida_recusa_coerente
    CHECK ((recusada_em IS NULL) = (motivo_recusa IS NULL));

COMMENT ON COLUMN entrega_falida.motivo_recusa IS
  'Por que a encomenda não virou missão: PONTO_LOTADO (sem vaga) ou SEM_PATROCINIO (patrocinador '
  'ausente, inativo ou sem saldo). NULL quando recusada_em é NULL.';
