-- =====================================================================
-- V902 — Seed de alertas e consentimentos (apenas dev/test)
--
-- Faixa 900+: roda depois de TODO schema, por construção. Ver ADR 0006 e a
-- seção Banco do CLAUDE.md. Este arquivo NÃO existe no perfil default/prod,
-- que só carrega db/migration.
--
-- Existe porque duas telas novas nasceriam vazias em dev: a central de
-- notificações (a tabela `alerta` só é escrita pela drenagem da outbox,
-- que exige uma missão concluída de verdade) e o painel de consentimentos
-- (nenhum caminho de escrita existia até agora, então TODO usuário aparecia
-- sem nenhuma escolha registrada).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Alertas da Alice: um lido e dois pendentes, para o contador da barra
-- superior mostrar 2 e a lista exercitar os dois estados visuais.
-- ---------------------------------------------------------------------
INSERT INTO alerta (id, usuario_id, tipo, titulo, corpo, missao_id, lido, criado_em) VALUES
    ('dddddddd-0000-0000-0000-000000000001',
     'bbbbbbbb-0000-0000-0000-000000000002',
     'MISSAO_CONCLUIDA',
     'Recompensa creditada',
     'Missão concluída. A recompensa já está na sua carteira.',
     NULL, TRUE,  NOW() - INTERVAL '3 days'),

    ('dddddddd-0000-0000-0000-000000000002',
     'bbbbbbbb-0000-0000-0000-000000000002',
     'MISSAO_CONCLUIDA',
     'Recompensa creditada',
     'Missão concluída e recompensa creditada. Você subiu para o nível 2.',
     NULL, FALSE, NOW() - INTERVAL '1 day'),

    ('dddddddd-0000-0000-0000-000000000003',
     'bbbbbbbb-0000-0000-0000-000000000002',
     'MISSAO_CONCLUIDA',
     'Recompensa creditada',
     'Missão concluída. A recompensa já está na sua carteira.',
     NULL, FALSE, NOW() - INTERVAL '2 hours'),

    -- Um do Bob, para que qualquer teste de isolamento de caixa tenha contra
    -- o que falhar: sem isto, um filtro por dono quebrado passaria despercebido.
    ('dddddddd-0000-0000-0000-000000000004',
     'bbbbbbbb-0000-0000-0000-000000000003',
     'MISSAO_CONCLUIDA',
     'Recompensa creditada',
     'Missão concluída. A recompensa já está na sua carteira.',
     NULL, FALSE, NOW() - INTERVAL '5 hours');

-- ---------------------------------------------------------------------
-- Consentimentos. A tabela é APPEND-ONLY: cada mudança é uma linha nova, e o
-- estado atual é a mais recente por tipo. A Alice aparece com o histórico
-- completo de NOTIFICACAO (concedeu, depois revogou) exatamente para que o
-- painel seja exercitado contra o caso que uma coluna sobrescrita esconderia.
-- ---------------------------------------------------------------------
INSERT INTO consentimento (id, usuario_id, tipo, concedido, versao_texto, ip, criado_em) VALUES
    ('eeeeeeee-0000-0000-0000-000000000001',
     'bbbbbbbb-0000-0000-0000-000000000002',
     'TERMOS',       TRUE,  '2026-08-01', NULL, NOW() - INTERVAL '30 days'),

    ('eeeeeeee-0000-0000-0000-000000000002',
     'bbbbbbbb-0000-0000-0000-000000000002',
     'LOCALIZACAO',  TRUE,  '2026-08-01', NULL, NOW() - INTERVAL '30 days'),

    ('eeeeeeee-0000-0000-0000-000000000003',
     'bbbbbbbb-0000-0000-0000-000000000002',
     'NOTIFICACAO',  TRUE,  '2026-08-01', NULL, NOW() - INTERVAL '30 days'),

    -- Revogação posterior: é ESTA linha que vale para o estado atual.
    ('eeeeeeee-0000-0000-0000-000000000004',
     'bbbbbbbb-0000-0000-0000-000000000002',
     'NOTIFICACAO',  FALSE, '2026-08-01', NULL, NOW() - INTERVAL '2 days'),

    ('eeeeeeee-0000-0000-0000-000000000005',
     'bbbbbbbb-0000-0000-0000-000000000003',
     'TERMOS',       TRUE,  '2026-08-01', NULL, NOW() - INTERVAL '20 days');
