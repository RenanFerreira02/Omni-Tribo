-- Fecha os dois BECOS SEM SAÍDA da máquina de estados, e dá à varredura o marco temporal que faltava.
--
-- O problema: EM_ANDAMENTO tinha uma única saída (CHECKIN) e AGUARDANDO_CONFIRMACAO tinha duas,
-- ambas do CRIADOR (CONFIRMAR, CONTESTAR). Nenhum ator — nem o executor, nem o criador, nem um
-- ADMIN — podia tirar a missão de lá quando a pessoa responsável simplesmente sumia. O pote de uma
-- missão TRIBO ficava em custódia PARA SEMPRE.
--
-- E a perda era invisível: a reconciliação compara ledger com projeção, e as duas continuam batendo
-- com tokens presos numa missão morta. Quem quebra é a CONSERVAÇÃO (SUM(carteiras) + SUM(potes)),
-- que é outra invariante. Um endpoint respondendo integro=true não prova que nada se perdeu.

-- 1. estado_desde: desde quando a missão está no status ATUAL.
--
-- Nenhum marco existente responde isso. janela_fim é o prazo de OFERTA — vira passado assim que
-- alguém aceita e não diz nada sobre execução; aceita_em é zerado no DESISTIR; criada_em é imóvel.
-- Sem esta coluna, varrer EM_ANDAMENTO exigiria inferir o instante lendo missao_evento, com um JOIN
-- por linha dentro do laço do job.
-- DEFAULT now() não é conveniência: é o que mantém esta migration compatível com os SEEDS.
--
-- Os seeds vivem na faixa 900+ e portanto rodam DEPOIS de todo schema — V900 já existe, já foi
-- aplicada em bancos de dev, e insere missões com lista de colunas explícita. Sem default, esta
-- coluna NOT NULL derrubaria o V900 com "null value in column estado_desde". A saída óbvia seria
-- editar o V900 para incluir a coluna, e ela está ERRADA: mudar o conteúdo de uma migration já
-- aplicada altera o checksum, e todo banco de dev existente passaria a falhar no boot com
-- "Migration checksum mismatch" — um erro que não aparece no CI, porque lá o banco nasce do zero.
--
-- E o default é semanticamente CERTO: uma linha inserida agora está no status dela desde agora.
ALTER TABLE missao ADD COLUMN estado_desde TIMESTAMPTZ NOT NULL DEFAULT now();

-- Backfill das linhas que já existiam: o melhor instante conhecido, do mais específico para o mais
-- genérico. Não é exato para missões antigas — não há como reconstruir o passado sem varrer a
-- trilha —, mas erra na direção segura: deixa a missão parecer MAIS NOVA do que é, o que ADIA a
-- expiração em vez de antecipá-la sobre alguém que está executando de boa-fé.
UPDATE missao SET estado_desde = COALESCE(concluida_em, aceita_em, criada_em)
 WHERE criada_em IS NOT NULL;

COMMENT ON COLUMN missao.estado_desde IS
  'Instante da última transição de status. Mantido por MissaoStateMachine.setStatus(status, quando); '
  'é o marco que a varredura de EM_ANDAMENTO e AGUARDANDO_CONFIRMACAO usa para medir abandono.';

-- Índices parciais, no molde de idx_missao_expiravel (V11): o predicado do job é exatamente
-- (status = X AND estado_desde < corte), e sendo parciais são uma fração da tabela — não varrem
-- missões já concluídas ou canceladas a cada execução.
CREATE INDEX idx_missao_execucao_travada ON missao (estado_desde)
    WHERE status = 'EM_ANDAMENTO';
CREATE INDEX idx_missao_confirmacao_travada ON missao (estado_desde)
    WHERE status = 'AGUARDANDO_CONFIRMACAO';

-- 2. Tipos novos na trilha, e limpeza de dois que nunca foram produzidos.
--
-- Drop e recreate porque o PostgreSQL não permite alterar CHECK no lugar — mesma sequência da V11.
-- Privilégio é por TABELA e sobrevive ao ALTER, então o REVOKE UPDATE, DELETE da V3 sobre
-- missao_evento continua valendo (MigracaoTest trava essa matriz).
ALTER TABLE missao_evento DROP CONSTRAINT ck_missao_evento_tipo;

ALTER TABLE missao_evento ADD CONSTRAINT ck_missao_evento_tipo CHECK (tipo IN (
    'PUBLICADA', 'ACEITA', 'DESISTIDA', 'INICIADA',
    'CHECK_IN_REGISTRADO',
    'CONFIRMADA', 'CONTESTADA', 'DISPUTA_RESOLVIDA',
    'CANCELADA', 'EXPIRADA',
    -- Três causas com desfechos econômicos DIFERENTES, e por isso três tipos. Um 'EXPIRADA'
    -- genérico faria a trilha ser incapaz de explicar por que um pote voltou aos financiadores:
    'EXECUCAO_EXPIRADA',      -- executor abandonou sem check-in  → EXPIRADA, estorna
    'CONFIRMACAO_EXPIRADA',   -- criador sumiu após o check-in    → CONCLUIDA, PAGA o executor
    'DESTRAVADA_POR_ADMIN'    -- saída manual com justificativa   → CANCELADA, estorna
));

-- Saíram 'CHECK_IN_REJEITADO' (a rejeição grava na tabela checkin, nunca aqui) e 'CONCLUIDA'
-- (reservada para uma F7 que foi entregue gravando CONFIRMADA/DISPUTA_RESOLVIDA). Nenhum caminho de
-- código produzia os dois; um tipo declarado e inalcançável é falsa promessa para quem lê a trilha.
-- Seguro remover do CHECK: como nada os emitia, nenhuma linha existente os usa.
