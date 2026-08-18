-- =============================================================================
-- V21 — Fim da Entrega Falida
--
-- Dá ao módulo logistica o que faltava para a tese do produto sair do papel: uma
-- entrega que falhou vira missão comunitária remunerada. O schema de entrega_falida
-- existe desde a V6, mas sem os dados que a conversão exige e sem a restrição que
-- torna o webhook idempotente.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Os dados que a transportadora precisa mandar, e que a V6 não previu.
--
-- LACUNA registrada em docs/auditoria/F3.md:138-142: sem peso e volume, converter
-- uma entrega falida em missão exigiria adivinhar — e são exatamente os dois campos
-- que CriacaoMissaoVerificador EXIGE para categoria ENTREGA, além de serem o insumo
-- de que CalculadoraDeRecompensa deriva a complexidade.
--
-- TODAS NULÁVEIS, e isso é decisão, não descuido. Os seeds V901/V903 já inseriram
-- linhas com lista de colunas explícita e JÁ FORAM APLICADOS em bancos de dev. Editar
-- um seed aplicado muda o checksum e derruba todo banco existente no boot com
-- "Migration checksum mismatch" — um erro que não aparece no CI, porque lá o banco
-- nasce do zero (é a armadilha que a V20 documentou nas linhas 20-25). Um NOT NULL
-- com DEFAULT resolveria o boot inventando um peso, o que é pior: passaria a existir
-- uma encomenda de "0 kg" que ninguém pesou.
--
-- Nulo aqui significa a verdade: esta linha nasceu antes do webhook e nunca teve o
-- dado capturado. Quem EXIGE os campos é a borda HTTP, no DTO do webhook, onde a
-- ausência vira 400 com o campo apontado.
-- -----------------------------------------------------------------------------
ALTER TABLE entrega_falida ADD COLUMN peso_kg   NUMERIC(6,2);
ALTER TABLE entrega_falida ADD COLUMN volume_l  NUMERIC(8,2);

-- Endereço ORIGINAL de entrega — para onde o executor leva a encomenda.
--
-- Não é redundante com ponto_custodia.ponto: o ponto é a ORIGEM da missão (onde a
-- encomenda está), e este é o DESTINO. A distância entre os dois é o trajeto que o
-- executor percorre, e é o que CalculadoraDeRecompensa remunera por km. Sem esta
-- coluna, distanciaM chega nula à fórmula, o adicional de distância é zero, e toda
-- entrega falida pagaria igual — a da esquina e a do outro lado do bairro.
ALTER TABLE entrega_falida ADD COLUMN destino            GEOGRAPHY(POINT,4326);
ALTER TABLE entrega_falida ADD COLUMN destino_cep        VARCHAR(8);
ALTER TABLE entrega_falida ADD COLUMN destino_logradouro VARCHAR(200);
ALTER TABLE entrega_falida ADD COLUMN destino_bairro     VARCHAR(100);
ALTER TABLE entrega_falida ADD COLUMN destino_cidade     VARCHAR(100);
ALTER TABLE entrega_falida ADD COLUMN destino_uf         VARCHAR(2);

-- Valor que a transportadora OFERECE pela missão.
--
-- É insumo do cálculo da recompensa em TOKEN e nada mais. NUNCA é copiado para
-- missao.valor_brl: ck_missao_economia (V15) exige valor_brl = 0 em toda missão, e o
-- ADR 0009 tirou o BRL do ciclo. Guardar aqui serve para auditar o que foi oferecido
-- e para sustentar o caso de negócio do patrocinador — patrocinar o pote custa menos
-- que o fracasso da entrega —, não para pagar ninguém em reais.
ALTER TABLE entrega_falida ADD COLUMN valor_ofertado_brl NUMERIC(12,2);

COMMENT ON COLUMN entrega_falida.valor_ofertado_brl IS
  'Valor oferecido pela transportadora. Insumo de CalculadoraDeRecompensa (versão 2 da fórmula); '
  'jamais gravado em missao.valor_brl, que ck_missao_economia trava em zero.';

-- -----------------------------------------------------------------------------
-- 2. Recusa por lotação, e por que NÃO é uma coluna de status.
--
-- A especificação pede que uma entrega chegando a um ponto SEM VAGA seja registrada
-- mesmo assim, sem virar missão. Isso colide com a invariante que MigracaoTest trava:
--
--   ponto_custodia.ocupacao = pendentes + convertidas cuja missão não concluiu
--
-- Uma recusa gravada com missao_id NULL é contada como "pendente" e exigiria
-- ocupacao + 1 num ponto que está lotado justamente por não caber mais nada. A causa
-- é que missao_id IS NULL passou a significar duas coisas incompatíveis: "está no
-- ponto esperando virar missão" e "nunca entrou no ponto". Só a primeira ocupa espaço.
--
-- A primeira ideia foi uma coluna status ('RECEBIDA'/'CONVERTIDA'/'RECUSADA'), e ela
-- NÃO SOBREVIVE ao mesmo problema de checksum do item 1: como esta migration roda
-- ANTES dos seeds (faixa 900+ é a última), as linhas de V901/V903 pegariam o DEFAULT,
-- e nenhum default distingue convertida de pendente — só editando os seeds, que é o
-- que não se pode fazer. Um timestamp nulável não precisa de backfill nenhum: nada
-- foi recusado antes deste código existir, então NULL já é o valor correto em todas
-- as linhas que existem.
-- -----------------------------------------------------------------------------
ALTER TABLE entrega_falida ADD COLUMN recusada_em TIMESTAMPTZ;

COMMENT ON COLUMN entrega_falida.recusada_em IS
  'Quando a encomenda foi recusada pelo ponto de custódia (sem vaga). NULL = não recusada. '
  'Linha recusada é registro do fato: não ocupa vaga e nunca gera missão.';

-- Recusada não vira missão. Vale para toda linha existente (recusada_em é NULL em
-- todas), então a constraint entra validada sem backfill.
ALTER TABLE entrega_falida ADD CONSTRAINT ck_entrega_falida_recusada_sem_missao
    CHECK (recusada_em IS NULL OR missao_id IS NULL);

-- convertida_em muda de significado, e o comentário da V6 passa a enganar.
-- Era "preenchido quando vira missão de retirada"; agora é o instante em que a
-- encomenda SAIU da custódia, carimbado junto com o decremento de ocupacao na
-- conclusão da missão. Quem quiser saber quando virou missão lê missao.criada_em
-- pelo missao_id, que já está aqui.
COMMENT ON COLUMN entrega_falida.convertida_em IS
  'Instante em que a encomenda saiu da custódia — carimbado na CONCLUSÃO da missão, junto com o '
  'decremento de ponto_custodia.ocupacao. Para saber quando virou missão, use missao.criada_em.';

-- -----------------------------------------------------------------------------
-- 3. Idempotência do webhook.
--
-- Hoje o mesmo webhook reenviado insere duas linhas: não há UNIQUE nem índice sobre
-- (transportadora, codigo_rastreio) em lugar nenhum do schema. Uma transportadora que
-- reenvia por timeout de rede — o caso normal, não o excepcional — criaria duas
-- encomendas, duas missões e dois incrementos de ocupação.
--
-- Chave COMPOSTA e CRUA, de propósito. A regra do CLAUDE.md que manda guardar
-- sha256(...) em vez da chave do cliente existe para quando a UNIQUE é GLOBAL e a
-- chave é escolhida livremente pelo cliente: lá, quem manda "1" recebe o replay do
-- vizinho. Aqui a unicidade é escopada por transportadora e o código de rastreio é
-- identificador de negócio emitido pela própria transportadora — não é segredo, e
-- precisa ser legível em suporte e em conciliação.
-- -----------------------------------------------------------------------------
ALTER TABLE entrega_falida ADD CONSTRAINT uk_entrega_falida_rastreio
    UNIQUE (transportadora, codigo_rastreio);

-- Sondagem da idempotência e do estoque do ponto. O UNIQUE acima já cria índice para
-- o primeiro; este cobre "o que está fisicamente neste ponto", que é a leitura da
-- validação de vaga e da baixa na conclusão.
CREATE INDEX idx_entrega_falida_ponto ON entrega_falida (ponto_custodia_id)
    WHERE recusada_em IS NULL;

-- -----------------------------------------------------------------------------
-- 4. Teto de notificação por usuário por hora.
--
-- A contagem é WHERE usuario_id = ? AND criado_em >= ?, e os índices da V8 são
-- (usuario_id) e (usuario_id, lido) parcial — nenhum ordena por tempo. Sem este,
-- cada notificação enviada varre todos os alertas históricos do destinatário.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_alerta_usuario_recente ON alerta (usuario_id, criado_em DESC);

-- -----------------------------------------------------------------------------
-- 5. Nível mínimo por missão.
--
-- Regra de Elegibilidade por Reputação do challenge: missão que envolve custódia de
-- pacote físico não fica visível para toda a base, e sim para membros com reputação
-- consolidada. Coluna por missão, e não constante no serviço, por três razões: o app
-- consegue exibir "faltam N níveis", a regra fica auditável junto com a missão que a
-- aplicou, e calibrar o mínimo depois não reescreve o passado.
--
-- DEFAULT 1 é o nível de qualquer conta recém-criada (RegraNivel.nivelPara(0) = 1),
-- então a coluna nasce inócua para tudo que já existe e para toda missão criada por
-- humano — só a conversão de entrega falida grava valor maior.
-- -----------------------------------------------------------------------------
ALTER TABLE missao ADD COLUMN nivel_minimo INT NOT NULL DEFAULT 1;

ALTER TABLE missao ADD CONSTRAINT ck_missao_nivel_minimo CHECK (nivel_minimo >= 1);

COMMENT ON COLUMN missao.nivel_minimo IS
  'Nível mínimo (derivado do XP por RegraNivel) exigido para ACEITAR. 1 = sem restrição.';

-- -----------------------------------------------------------------------------
-- 6. Usuário-sistema: o criador que a conversão não tinha.
--
-- missao.criador_id é NOT NULL REFERENCES usuario(id), e publicar exige
-- AtorEsperado.CRIADOR, que compara IDENTIDADE e não papel — nem um ADMIN publica
-- missão alheia. AtorMissao.sistema() tem usuarioId nulo, então o webhook
-- simplesmente não tinha quem fosse o dono da missão que ele cria.
--
-- Esta linha resolve isso sem inventar evento novo na máquina de estados nem mexer no
-- CHECK fechado ck_missao_evento_tipo: AtorMissao só exige usuarioId quando o papel
-- NÃO é SISTEMA, então AtorMissao(ID_SISTEMA, SISTEMA) é legal e satisfaz ehMesmo().
--
-- Vai no SCHEMA e não no seed porque produção também precisa dela — é dado de
-- referência, não dado de demonstração. É a mesma distinção que separa db/migration
-- de db/seed.
--
-- status = 'INATIVO' é a trava: AutenticacaoService recusa qualquer status != ATIVO,
-- então nenhuma senha autentica esta conta e nenhum token é emitido para ela. O
-- senha_hash é um marcador que não tem forma de hash Argon2 e por isso não pode
-- casar com nada — a coluna é NOT NULL e precisa de algum valor.
-- -----------------------------------------------------------------------------
INSERT INTO usuario (id, nome, email, senha_hash, handle, papel, status, xp, nivel)
VALUES ('00000000-0000-0000-0000-000000000001',
        'Omni-Tribo',
        'sistema@omnitribo.local',
        'CONTA-DE-SISTEMA-SEM-SENHA',
        'omnitribo',
        'USUARIO',
        'INATIVO',
        0,
        1);
