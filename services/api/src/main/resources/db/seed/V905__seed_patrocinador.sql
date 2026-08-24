-- =============================================================================
-- V905 — Patrocinador de demonstração (perfis dev e test)
--
-- Sem esta migration não existe um único caminho verde para o webhook depois da
-- V23: `transportadora-dev` é o slug que application-dev.yml integra e que
-- tools/carrier-mock/enviar.sh envia, e sem um patrocinador ligado a ele TODA
-- entrega falida cairia no desfecho SEM_PATROCINIO. O caminho feliz do mock
-- deixaria de existir.
--
-- Faixa 900+ pela razão de sempre, e aqui ela é dupla: garante que roda depois de
-- todo o schema E depois de todos os outros seeds, o que a seção 3 exige.
-- =============================================================================

-- -----------------------------------------------------------------------
-- 1. O titular.
--
-- Mesmo molde do usuário-sistema da V21: status INATIVO é a trava, porque
-- AutenticacaoService recusa qualquer status diferente de ATIVO — nenhuma senha
-- autentica esta conta e nenhum token é emitido para ela. O senha_hash é um
-- marcador que não tem forma de Argon2 e por isso não casa com nada; a coluna é
-- NOT NULL e precisa de algum valor.
--
-- Diferença em relação à V21: aquela linha vai no SCHEMA porque produção também
-- precisa dela (é alvo de FK de toda missão automática). Esta vai no SEED porque
-- é dado de DEMONSTRAÇÃO — em produção o patrocinador nasce pelo endpoint ADMIN,
-- com o nome e o slug do contrato real. É a mesma distinção que separa
-- db/migration de db/seed.
--
-- tribo_id fica NULL: patrocinador não pertence a bairro nenhum, e é justamente
-- por isso que ele não passa por FinanciamentoService.validarAutorizacao, que
-- exige afiliação. O caminho dele é outro — ver ADR 0024 §5.
-- -----------------------------------------------------------------------
INSERT INTO usuario (id, nome, email, senha_hash, handle, papel, status, xp, nivel)
VALUES ('bbbbbbbb-0000-0000-0000-000000000950',
        'Transportadora Dev (patrocinador)',
        'patrocinador@transportadora-dev.local',
        'CONTA-DE-PATROCINADOR-SEM-SENHA',
        'transportadora_dev',
        'PATROCINADOR',
        'INATIVO',
        0,
        1);

INSERT INTO patrocinador (id, usuario_id, transportadora_slug, nome, ativo, criado_em)
VALUES ('77777777-0000-0000-0000-000000000950',
        'bbbbbbbb-0000-0000-0000-000000000950',
        -- Casa com app.webhooks.segredos de application-dev.yml e com o default de
        -- TRANSPORTADORA em tools/carrier-mock/enviar.sh. Slug em minúsculas: o
        -- HmacWebhookFilter normaliza antes de publicar o atributo verificado.
        'transportadora-dev',
        'Transportadora Dev',
        TRUE,
        NOW());

-- -----------------------------------------------------------------------
-- 1b. O patrocinador do perfil de TESTE.
--
-- Os slugs integrados por HMAC são diferentes em cada perfil: dev usa
-- `transportadora-dev` (application-dev.yml) e test usa `transportadora-teste`
-- (src/test/resources/application-test.yml). Este seed roda nos DOIS, então
-- precisa cobrir os dois — sem esta linha, todo teste de caminho feliz do webhook
-- responderia SEM_PATROCINIO, que é o comportamento correto para uma
-- transportadora sem contrato e a resposta errada para o que aqueles testes medem.
--
-- `outra-transportadora`, o terceiro slug integrado em test, fica DELIBERADAMENTE
-- sem patrocinador. Ela passa a ser a fixture do caminho SEM_PATROCINIO: uma
-- transportadora com HMAC válido, que atravessa o filtro e chega ao serviço, e
-- mesmo assim não converte. Sem uma assim, testar aquele desfecho exigiria
-- desativar o patrocinador de outra no meio do teste — estado compartilhado que
-- dependeria da ordem de execução, porque o container não é truncado entre testes.
-- É a mesma razão pela qual a V904 semeou um ponto lotado em vez de encher um.
-- -----------------------------------------------------------------------
INSERT INTO usuario (id, nome, email, senha_hash, handle, papel, status, xp, nivel)
VALUES ('bbbbbbbb-0000-0000-0000-000000000951',
        'Transportadora Teste (patrocinador)',
        'patrocinador@transportadora-teste.local',
        'CONTA-DE-PATROCINADOR-SEM-SENHA',
        'transportadora_teste',
        'PATROCINADOR',
        'INATIVO',
        0,
        1);

INSERT INTO patrocinador (id, usuario_id, transportadora_slug, nome, ativo, criado_em)
VALUES ('77777777-0000-0000-0000-000000000951',
        'bbbbbbbb-0000-0000-0000-000000000951',
        'transportadora-teste',
        'Transportadora Teste',
        TRUE,
        NOW());

-- -----------------------------------------------------------------------
-- 2. Carteira e aporte.
--
-- O par carteira + lançamento é OBRIGATORIAMENTE coerente, não decorativo:
-- MigracaoTest.soma_do_ledger_igual_ao_saldo_de_cada_carteira_semeada varre TODA
-- carteira e exige que a soma do ledger bata com saldo_tokens. Semear a carteira
-- com 5000 e nenhum lançamento reprovaria o teste — e reprovaria com razão, porque
-- é exatamente a forma de corrupção mais grave que a reconciliação existe para
-- achar: saldo positivo sem origem.
--
-- 5.000 tokens: as recompensas semeadas ficam na casa de 28 a 41 tokens, então o
-- saldo cobre mais de cem conversões de entrega falida. É folga proposital — um
-- saldo apertado faria a demo cair em SEM_PATROCINIO no meio da apresentação, e o
-- desfecho correto pareceria defeito.
--
-- APORTE_PATROCINADOR é o único motivo que credita sem debitar contraparte. Aqui
-- ele representa o aporte que, em produção, entra pelo endpoint ADMIN.
-- -----------------------------------------------------------------------
INSERT INTO carteira (id, usuario_id, saldo_brl, saldo_tokens, versao) VALUES
    ('eeeeeeee-0000-0000-0000-000000000950',
     'bbbbbbbb-0000-0000-0000-000000000950',
     0.00, 5000, 1),
    ('eeeeeeee-0000-0000-0000-000000000951',
     'bbbbbbbb-0000-0000-0000-000000000951',
     0.00, 5000, 1);

INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                        missao_id, contraparte_carteira_id, chave_idempotencia,
                        saldo_apos_brl, saldo_apos_tokens, criado_em)
VALUES
    ('ffffffff-0000-0000-0000-000000000950',
     'eeeeeeee-0000-0000-0000-000000000950',
     'CREDITO', 'APORTE_PATROCINADOR', 0.00, 5000,
     NULL, NULL,
     'seed-aporte-transportadora-dev',
     0.00, 5000,
     NOW()),
    ('ffffffff-0000-0000-0000-000000000951',
     'eeeeeeee-0000-0000-0000-000000000951',
     'CREDITO', 'APORTE_PATROCINADOR', 0.00, 5000,
     NULL, NULL,
     'seed-aporte-transportadora-teste',
     0.00, 5000,
     NOW());

-- -----------------------------------------------------------------------
-- 3. Backfill de missao.fonte_pote — a segunda passada.
--
-- A V23 já fez este mesmo UPDATE, e ele NÃO basta: os seeds V900 a V904 rodam
-- DEPOIS dela (faixa 900+ é a última) e inserem missões com lista de colunas
-- explícita, sem fonte_pote. Toda missão TRIBO/COLETA semeada pegou o
-- DEFAULT 'CUNHAGEM' e, sem esta correção, a conclusão delas passaria a CUNHAR
-- token enquanto o pote financiado ficaria preso para sempre — quebrando a
-- conservação justamente nas duas categorias onde ela já valia.
--
-- Editar os seeds para incluir a coluna resolveria em clone novo e derrubaria
-- todo banco de dev existente com "Migration checksum mismatch", um erro que o CI
-- nunca reproduz porque lá o banco nasce do zero. É a armadilha registrada na
-- V21:18-28 e na V20:20-25.
--
-- Idempotente e inócuo em clone novo: nas linhas que a V23 já corrigiu, o UPDATE
-- não muda nada.
-- -----------------------------------------------------------------------
UPDATE missao SET fonte_pote = 'COMUNIDADE'
 WHERE categoria IN ('TRIBO', 'COLETA')
   AND fonte_pote <> 'COMUNIDADE';

-- As missões de retirada semeadas (V901/V903) nasceram de entrega falida e, no
-- mundo de hoje, seriam patrocinadas. Mas elas NÃO têm pote — foram semeadas antes
-- desta fase existir —, e marcá-las como PATROCINADOR faria a conclusão delas
-- falhar com 422 por pote insuficiente. Ficam como CUNHAGEM, que é a verdade
-- histórica: foram criadas quando ENTREGA cunhava. O ADR 0024 registra que o
-- corte é por data de criação, não por categoria.
