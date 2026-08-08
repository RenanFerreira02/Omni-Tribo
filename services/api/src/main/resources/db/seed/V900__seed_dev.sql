-- =============================================================================
-- V900 — Seed de desenvolvimento (domínio Leroy Merlin / Sociedade 5.0)
-- Carregado APENAS nos perfis dev e test (ver application-dev.yml / application-test.yml).
-- NÃO incluir em application.yml base (produção não carrega seed).
--
-- POR QUE 900, E NÃO 9: a versão do Flyway é uma sequência GLOBAL, não por pasta.
-- Antes, este arquivo era V9 e ficava NO MEIO da faixa do schema — o V11 rodava
-- depois dele e precisava reescrever os dados aqui inseridos, e um V9 novo em
-- db/migration teria derrubado dev e test com "more than one migration with
-- version 9". A faixa 900+ garante POR CONSTRUÇÃO que o seed é sempre o último a
-- rodar, e libera db/migration para crescer V12, V13... sem colisão possível.
--
-- CONSEQUÊNCIA: rodando por último, o seed vê o schema já na forma final. Os dados
-- abaixo são gravados em forma final também — status 'ABERTA' (nunca o antigo
-- 'DISPONIVEL', que o CHECK do V11 rejeita) e senha já com o prefixo '{bcrypt}'
-- que o DelegatingPasswordEncoder exige. Nenhuma migration posterior precisa
-- corrigir este arquivo. (O antigo V10 existia só para adicionar esse prefixo;
-- foi incorporado aqui.)
--
-- Credenciais dos usuários → docs/INFRA.md (seção "Seed de Dev")
-- Senha de todos os usuários seed: Senha@123
-- Coordenadas: região Pinheiros / Vila Madalena, São Paulo
-- =============================================================================

-- -----------------------------------------------------------------------
-- Tribos (IDs fixos para facilitar referências nos testes)
-- -----------------------------------------------------------------------
INSERT INTO tribo (id, nome, bairro, criada_em) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001', 'Tribo Pinheiros',      'Pinheiros',      NOW()),
    ('aaaaaaaa-0000-0000-0000-000000000002', 'Tribo Vila Madalena',  'Vila Madalena',  NOW()),
    ('aaaaaaaa-0000-0000-0000-000000000003', 'Tribo Jardim América', 'Jardim América', NOW());

-- -----------------------------------------------------------------------
-- Usuários. Hash via pgcrypto, com o prefixo de algoritmo que o
-- DelegatingPasswordEncoder exige: '{bcrypt}' || crypt('Senha@123', gen_salt('bf',10)).
-- Sem o prefixo o encoder não resolve o algoritmo e todo login do seed falha.
-- -----------------------------------------------------------------------
INSERT INTO usuario (id, nome, email, senha_hash, handle, tribo_id, xp, nivel, streak, rating, papel, status, criado_em, atualizado_em, versao) VALUES
    ('bbbbbbbb-0000-0000-0000-000000000001',
     'Administrador',
     'admin@omnitribo.dev',
     '{bcrypt}' || crypt('Senha@123', gen_salt('bf', 10)),
     'admin',
     'aaaaaaaa-0000-0000-0000-000000000001',
     0, 1, 0, 0.0, 'ADMIN', 'ATIVO', NOW(), NOW(), 0),

    ('bbbbbbbb-0000-0000-0000-000000000002',
     'Alice Ferreira',
     'alice@omnitribo.dev',
     '{bcrypt}' || crypt('Senha@123', gen_salt('bf', 10)),
     'alice',
     'aaaaaaaa-0000-0000-0000-000000000001',
     320, 3, 7, 4.8, 'USUARIO', 'ATIVO', NOW(), NOW(), 0),

    ('bbbbbbbb-0000-0000-0000-000000000003',
     'Bob Oliveira',
     'bob@omnitribo.dev',
     '{bcrypt}' || crypt('Senha@123', gen_salt('bf', 10)),
     'bob',
     'aaaaaaaa-0000-0000-0000-000000000002',
     180, 2, 3, 4.5, 'USUARIO', 'ATIVO', NOW(), NOW(), 0),

    ('bbbbbbbb-0000-0000-0000-000000000004',
     'Carol Santos',
     'carol@omnitribo.dev',
     '{bcrypt}' || crypt('Senha@123', gen_salt('bf', 10)),
     'carol',
     'aaaaaaaa-0000-0000-0000-000000000002',
     250, 2, 5, 4.7, 'USUARIO', 'ATIVO', NOW(), NOW(), 0),

    ('bbbbbbbb-0000-0000-0000-000000000005',
     'Diana Lima',
     'diana@omnitribo.dev',
     '{bcrypt}' || crypt('Senha@123', gen_salt('bf', 10)),
     'diana',
     'aaaaaaaa-0000-0000-0000-000000000003',
     410, 4, 12, 5.0, 'USUARIO', 'ATIVO', NOW(), NOW(), 0),

    ('bbbbbbbb-0000-0000-0000-000000000006',
     'Erik Costa',
     'erik@omnitribo.dev',
     '{bcrypt}' || crypt('Senha@123', gen_salt('bf', 10)),
     'erik',
     'aaaaaaaa-0000-0000-0000-000000000003',
     90, 1, 1, 4.2, 'USUARIO', 'ATIVO', NOW(), NOW(), 0);

-- -----------------------------------------------------------------------
-- Pontos de custódia (≥1 LOJA; coordenadas reais da região)
-- -----------------------------------------------------------------------
INSERT INTO ponto_custodia (id, codigo, tipo, apelido, ponto, tribo_id, capacidade, ocupacao, ativo, criado_em) VALUES
    -- LOJA: Leroy Merlin Pinheiros (R. Teodoro Sampaio)
    ('cccccccc-0000-0000-0000-000000000001',
     'LM-PIN-001', 'LOJA',
     'Leroy Merlin Pinheiros',
     ST_SetSRID(ST_MakePoint(-46.6934, -23.5640), 4326)::geography,
     'aaaaaaaa-0000-0000-0000-000000000001',
     50, 3, TRUE, NOW()),

    -- LOCKER: Consolação
    ('cccccccc-0000-0000-0000-000000000002',
     'LK-CON-001', 'LOCKER',
     'LOCKER Consolação',
     ST_SetSRID(ST_MakePoint(-46.6573, -23.5558), 4326)::geography,
     'aaaaaaaa-0000-0000-0000-000000000001',
     12, 2, TRUE, NOW()),

    -- PORTARIA: Edifício Solar (endereço fictício em Pinheiros)
    ('cccccccc-0000-0000-0000-000000000003',
     'PT-PIN-001', 'PORTARIA',
     'Portaria Ed. Solar Pinheiros',
     ST_SetSRID(ST_MakePoint(-46.6970, -23.5680), 4326)::geography,
     'aaaaaaaa-0000-0000-0000-000000000001',
     5, 1, TRUE, NOW()),

    -- VIZINHO: morador de confiança da tribo Vila Madalena
    ('cccccccc-0000-0000-0000-000000000004',
     'VZ-VMA-001', 'VIZINHO',
     'Vizinho Rua Girassol',
     ST_SetSRID(ST_MakePoint(-46.6893, -23.5530), 4326)::geography,
     'aaaaaaaa-0000-0000-0000-000000000002',
     3, 0, TRUE, NOW()),

    -- LOCKER: Vila Madalena
    ('cccccccc-0000-0000-0000-000000000005',
     'LK-VMA-001', 'LOCKER',
     'LOCKER Vila Madalena',
     ST_SetSRID(ST_MakePoint(-46.6921, -23.5565), 4326)::geography,
     'aaaaaaaa-0000-0000-0000-000000000002',
     10, 4, TRUE, NOW());

-- -----------------------------------------------------------------------
-- Missões (12 — domínio Leroy Merlin: ENTREGA/COLETA/TRIBO/AJUDA)
-- Coordenadas na região Pinheiros/Vila Madalena, São Paulo
-- -----------------------------------------------------------------------
INSERT INTO missao (id, criador_id, executor_id, categoria, titulo, descricao, status,
                    xp_recompensa, valor_brl, tokens_recompensa, complexidade, versao_formula,
                    origem, destino, ponto_custodia_id,
                    cep, logradouro, bairro, cidade, uf,
                    raio_checkin_m, peso_kg, volume_l,
                    janela_inicio, janela_fim, criada_em, aceita_em, concluida_em, versao)
VALUES

-- ===== ENTREGA (4) — itens de reforma no ponto de custódia =====

-- E1: Tinta acrílica branca 10L
('dddddddd-0000-0000-0000-000000000001',
 'bbbbbbbb-0000-0000-0000-000000000001',
 'bbbbbbbb-0000-0000-0000-000000000002',  -- executor: alice
 'ENTREGA',
 'Entregar tinta acrílica branca 18L',
 'Caixa com 2 galões de tinta acrílica premium branca (9L cada) parada no LOCKER Consolação. '
 'Item pesado — use carrinho. Entregar ao morador do apt 42.',
 'CONCLUIDA',
 123, 0.00, 41, 'MEDIA', 1,
 ST_SetSRID(ST_MakePoint(-46.6573, -23.5558), 4326)::geography,  -- origem: LOCKER Consolação
 ST_SetSRID(ST_MakePoint(-46.6600, -23.5570), 4326)::geography,  -- destino: endereço do destinatário
 'cccccccc-0000-0000-0000-000000000002',
 '01302000', 'R. da Consolação', 'Consolação', 'São Paulo', 'SP',
 50, 19.00, 18.0,
 NOW() - INTERVAL '3 days', NOW() - INTERVAL '1 day',
 NOW() - INTERVAL '3 days', NOW() - INTERVAL '2 days 12 hours', NOW() - INTERVAL '1 day', 0),

-- E2: Caixa de porcelanato 60×60 (12 peças)
('dddddddd-0000-0000-0000-000000000002',
 'bbbbbbbb-0000-0000-0000-000000000001',
 'bbbbbbbb-0000-0000-0000-000000000005',  -- executor: diana
 'ENTREGA',
 'Entregar caixa de porcelanato 60×60 — 12 peças',
 'Caixa de porcelanato Leroy Merlin ref. POR-6060-CZ na portaria do Ed. Solar Pinheiros. '
 'Peso elevado — obrigatório carrinho ou dois entregadores. Frágil.',
 'CONCLUIDA',
 168, 0.00, 56, 'PESADA', 1,
 ST_SetSRID(ST_MakePoint(-46.6970, -23.5680), 4326)::geography,  -- origem: Portaria Ed. Solar
 ST_SetSRID(ST_MakePoint(-46.6950, -23.5660), 4326)::geography,
 'cccccccc-0000-0000-0000-000000000003',
 '05427000', 'R. Teodoro Sampaio', 'Pinheiros', 'São Paulo', 'SP',
 50, 28.00, 35.0,
 NOW() - INTERVAL '5 days', NOW() - INTERVAL '2 days',
 NOW() - INTERVAL '5 days', NOW() - INTERVAL '4 days', NOW() - INTERVAL '2 days', 0),

-- E3: Torneira de cozinha + kit de acessórios
('dddddddd-0000-0000-0000-000000000003',
 'bbbbbbbb-0000-0000-0000-000000000001',
 NULL,
 'ENTREGA',
 'Entregar torneira de cozinha e kit de acessórios',
 'Pacote com torneira monocomando e kit de fixação na Leroy Merlin Pinheiros. '
 'Item de tamanho médio, sem risco de quebra se manuseado com cuidado.',
 'ABERTA',
 69, 0.00, 23, 'LEVE', 1,
 ST_SetSRID(ST_MakePoint(-46.6934, -23.5640), 4326)::geography,  -- origem: LM Pinheiros
 ST_SetSRID(ST_MakePoint(-46.6880, -23.5620), 4326)::geography,
 'cccccccc-0000-0000-0000-000000000001',
 '05425000', 'R. Teodoro Sampaio', 'Pinheiros', 'São Paulo', 'SP',
 50, 3.20, 5.0,
 NOW() + INTERVAL '1 day', NOW() + INTERVAL '4 days',
 NOW(), NULL, NULL, 0),

-- E4: Kit de luminárias de teto (3 peças)
('dddddddd-0000-0000-0000-000000000004',
 'bbbbbbbb-0000-0000-0000-000000000001',
 NULL,
 'ENTREGA',
 'Entregar kit de luminárias de teto — 3 peças',
 'Caixa com 3 luminárias spot LED embutidas. Parada no LOCKER Vila Madalena. '
 'Item frágil — transportar na posição vertical.',
 'ABERTA',
 72, 0.00, 24, 'LEVE', 1,
 ST_SetSRID(ST_MakePoint(-46.6921, -23.5565), 4326)::geography,  -- origem: LOCKER Vila Madalena
 ST_SetSRID(ST_MakePoint(-46.6900, -23.5550), 4326)::geography,
 'cccccccc-0000-0000-0000-000000000005',
 '05434020', 'R. Girassol', 'Vila Madalena', 'São Paulo', 'SP',
 50, 4.50, 15.0,
 NOW() + INTERVAL '2 days', NOW() + INTERVAL '5 days',
 NOW(), NULL, NULL, 0),

-- ===== COLETA (3) — logística reversa =====

-- C1: Devolução de embalagens de tinta
('dddddddd-0000-0000-0000-000000000005',
 'bbbbbbbb-0000-0000-0000-000000000001',
 'bbbbbbbb-0000-0000-0000-000000000004',  -- executor: carol
 'COLETA',
 'Coletar embalagens de tinta para descarte correto',
 'Retirar 4 galões vazios de tinta da residência e levar à Leroy Merlin Pinheiros '
 'para descarte no Ecoponto Leroy. O estabelecimento devolve vale-desconto.',
 'CONCLUIDA',
 105, 0.00, 35, 'MEDIA', 1,
 ST_SetSRID(ST_MakePoint(-46.6880, -23.5610), 4326)::geography,  -- origem: residência
 ST_SetSRID(ST_MakePoint(-46.6934, -23.5640), 4326)::geography,  -- destino: LM Pinheiros
 NULL,
 '05432020', 'Al. Ministro Rocha Azevedo', 'Jardim Paulista', 'São Paulo', 'SP',
 80, 6.00, 12.0,
 NOW() - INTERVAL '2 days', NOW() - INTERVAL '1 day',
 NOW() - INTERVAL '2 days', NOW() - INTERVAL '1 day 12 hours', NOW() - INTERVAL '1 day', 0),

-- C2: Devolução de porcelanato trincado
('dddddddd-0000-0000-0000-000000000006',
 'bbbbbbbb-0000-0000-0000-000000000001',
 'bbbbbbbb-0000-0000-0000-000000000004',  -- executor: carol
 'COLETA',
 'Devolver caixa de porcelanato trincado para troca',
 'Caixa com porcelanato com 3 peças trincadas. Levar à Leroy Merlin para registro '
 'de troca com nota fiscal. Embalar bem para não fragmentar no transporte.',
 'CONCLUIDA',
 111, 0.00, 37, 'MEDIA', 1,
 ST_SetSRID(ST_MakePoint(-46.6960, -23.5670), 4326)::geography,
 ST_SetSRID(ST_MakePoint(-46.6934, -23.5640), 4326)::geography,
 NULL,
 '05425020', 'R. Pedroso Alvarenga', 'Pinheiros', 'São Paulo', 'SP',
 80, 10.00, 14.0,
 NOW() - INTERVAL '4 days', NOW() - INTERVAL '2 days',
 NOW() - INTERVAL '4 days', NOW() - INTERVAL '3 days', NOW() - INTERVAL '2 days', 0),

-- C3: Retirada de entulho de obra de drywall
('dddddddd-0000-0000-0000-000000000007',
 'bbbbbbbb-0000-0000-0000-000000000001',
 NULL,
 'COLETA',
 'Retirar restos de drywall de obra pequena',
 'Dois sacos com restos de placa de drywall e gesso. Levar ao ponto de coleta de '
 'entulho da prefeitura no bairro. Necessário carro ou van pequena.',
 'ABERTA',
 135, 0.00, 45, 'MEDIA', 1,
 ST_SetSRID(ST_MakePoint(-46.6905, -23.5540), 4326)::geography,
 ST_SetSRID(ST_MakePoint(-46.6870, -23.5500), 4326)::geography,
 NULL,
 '05434010', 'R. Harmonia', 'Vila Madalena', 'São Paulo', 'SP',
 100, 22.00, 60.0,
 NOW() + INTERVAL '1 day', NOW() + INTERVAL '3 days',
 NOW(), NULL, NULL, 0),

-- ===== TRIBO (3) — mutirão comunitário =====

-- T1: Mutirão de pintura da escada do prédio
('dddddddd-0000-0000-0000-000000000008',
 'bbbbbbbb-0000-0000-0000-000000000001',
 'bbbbbbbb-0000-0000-0000-000000000006',  -- executor: erik
 'TRIBO',
 'Mutirão: pintura da escada de serviço do Ed. Pinheiros Verde',
 'Pintar escada de serviço com tinta já comprada e custeada pela tribo. '
 'Material no local. Trazer rolo, bandeja e EPI (máscara e óculos). '
 'Levar 3h com 3 pessoas.',
 'CONCLUIDA',
 114, 0.00, 38, 'MEDIA', 1,
 ST_SetSRID(ST_MakePoint(-46.6980, -23.5690), 4326)::geography,
 NULL,
 NULL,
 '05426010', 'R. dos Pinheiros', 'Pinheiros', 'São Paulo', 'SP',
 80, NULL, NULL,
 NOW() - INTERVAL '6 days', NOW() - INTERVAL '4 days',
 NOW() - INTERVAL '6 days', NOW() - INTERVAL '5 days', NOW() - INTERVAL '4 days', 0),

-- T2: Instalação de trilho de LED no corredor da vila
('dddddddd-0000-0000-0000-000000000009',
 'bbbbbbbb-0000-0000-0000-000000000001',
 NULL,
 'TRIBO',
 'Mutirão: instalar trilho de LED no corredor da Vila Harmonia',
 'Material (trilho LED 5m + transformadores) custeado coletivamente pela tribo. '
 'Precisa de 2 pessoas e alguém com conhecimento básico de elétrica. '
 'Material entregue no local pelo organizador.',
 'ABERTA',
 114, 0.00, 38, 'MEDIA', 1,
 ST_SetSRID(ST_MakePoint(-46.6912, -23.5545), 4326)::geography,
 NULL,
 NULL,
 '05434020', 'R. Harmonia', 'Vila Madalena', 'São Paulo', 'SP',
 80, NULL, NULL,
 NOW() + INTERVAL '3 days', NOW() + INTERVAL '5 days',
 NOW(), NULL, NULL, 0),

-- T3: Reparo de calçada com material da tribo
('dddddddd-0000-0000-0000-000000000010',
 'bbbbbbbb-0000-0000-0000-000000000001',
 NULL,
 'TRIBO',
 'Mutirão: tampar buraco na calçada com concreto da tribo',
 'Buraco de ~30cm na calçada da R. Cardeal Arcoverde. Saco de concreto já '
 'adquirido com verba da tribo Jardim América. Precisamos de 4 pessoas e 2h.',
 'ABERTA',
 114, 0.00, 38, 'MEDIA', 1,
 ST_SetSRID(ST_MakePoint(-46.6730, -23.5720), 4326)::geography,
 NULL,
 NULL,
 '01310100', 'R. Cardeal Arcoverde', 'Jardim América', 'São Paulo', 'SP',
 80, NULL, NULL,
 NOW() + INTERVAL '2 days', NOW() + INTERVAL '4 days',
 NOW(), NULL, NULL, 0),

-- ===== AJUDA (2) — item volumoso =====

-- A1: Carregar armário 6 portas até o 3º andar
('dddddddd-0000-0000-0000-000000000011',
 'bbbbbbbb-0000-0000-0000-000000000001',
 'bbbbbbbb-0000-0000-0000-000000000005',  -- executor: diana
 'AJUDA',
 'Ajuda para carregar armário 6 portas do térreo ao 3º andar',
 'Armário modulado MDF branco retirado da Leroy Merlin Pinheiros. '
 'Prédio sem elevador. São 3 volumes grandes. Precisamos de 3 pessoas fortes. '
 'O morador já está no local para receber.',
 'CONCLUIDA',
 240, 0.00, 80, 'PESADA', 1,
 ST_SetSRID(ST_MakePoint(-46.6934, -23.5640), 4326)::geography,  -- ponto de retirada: LM
 ST_SetSRID(ST_MakePoint(-46.6945, -23.5650), 4326)::geography,
 NULL,
 '05425050', 'R. Teodoro Sampaio', 'Pinheiros', 'São Paulo', 'SP',
 60, 68.00, 120.0,
 NOW() - INTERVAL '7 days', NOW() - INTERVAL '5 days',
 NOW() - INTERVAL '7 days', NOW() - INTERVAL '6 days', NOW() - INTERVAL '5 days', 0),

-- A2: Montagem de estante modular de MDF
('dddddddd-0000-0000-0000-000000000012',
 'bbbbbbbb-0000-0000-0000-000000000001',
 NULL,
 'AJUDA',
 'Ajuda para montar estante modular de MDF — 5 módulos',
 'Estante Leroy Merlin modelo Planck 5 módulos. Instruções incluídas na caixa. '
 'Precisa de 2 pessoas e ~2h. Chaves e parafusos fornecidos. '
 'A moradora é idosa e não consegue sozinha.',
 'ABERTA',
 90, 0.00, 30, 'MEDIA', 1,
 ST_SetSRID(ST_MakePoint(-46.6900, -23.5535), 4326)::geography,
 NULL,
 NULL,
 '05435010', 'R. Wisard', 'Vila Madalena', 'São Paulo', 'SP',
 60, NULL, NULL,
 NOW() + INTERVAL '1 day', NOW() + INTERVAL '3 days',
 NOW(), NULL, NULL, 0);

-- -----------------------------------------------------------------------
-- Carteiras (uma por usuário)
-- -----------------------------------------------------------------------
INSERT INTO carteira (id, usuario_id, saldo_brl, saldo_tokens, versao) VALUES
    ('eeeeeeee-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000001', 0.00,   0, 0),
    ('eeeeeeee-0000-0000-0000-000000000002', 'bbbbbbbb-0000-0000-0000-000000000002', 0.00,  41, 1),
    ('eeeeeeee-0000-0000-0000-000000000003', 'bbbbbbbb-0000-0000-0000-000000000003', 0.00, 178, 2),
    ('eeeeeeee-0000-0000-0000-000000000004', 'bbbbbbbb-0000-0000-0000-000000000004', 0.00,  72, 2),
    ('eeeeeeee-0000-0000-0000-000000000005', 'bbbbbbbb-0000-0000-0000-000000000005', 0.00,  99, 2),
    ('eeeeeeee-0000-0000-0000-000000000006', 'bbbbbbbb-0000-0000-0000-000000000006', 0.00,  38, 1);

-- -----------------------------------------------------------------------
-- Lançamentos — ledger coerente com os saldos acima.
-- saldo_apos_* reflete o saldo acumulado após cada lançamento por carteira.
-- Invariante: SUM(CASE sinal WHEN 'CREDITO' THEN valor ELSE -valor END) = saldo_atual
-- -----------------------------------------------------------------------
-- alice (carteira eeeeeeee-0000-0000-0000-000000000002): tokens=41
-- Missão E1 concluída, com alice como executora — o crédito é a recompensa dela.
INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                        missao_id, contraparte_carteira_id, chave_idempotencia,
                        saldo_apos_brl, saldo_apos_tokens, criado_em)
VALUES
    ('ffffffff-0000-0000-0000-000000000001',
     'eeeeeeee-0000-0000-0000-000000000002',
     'CREDITO', 'RECOMPENSA_MISSAO', 0.00, 41,
     'dddddddd-0000-0000-0000-000000000001', NULL,
     'seed-idem-alice-e1-tk',
     0.00, 41,
     NOW() - INTERVAL '1 day');

-- bob (carteira eeeeeeee-0000-0000-0000-000000000003): tokens=178
--
-- ATENÇÃO — bob NÃO é o executor da missão E2; diana é. Este crédito representa bob como
-- CO-EXECUTOR, e é ficção deliberada do seed: o schema tem um único executor_id, então
-- co-participação não é representável. Mantido para que o extrato do app tenha mais de uma
-- origem de crédito. Se a co-participação virar requisito real, ela precisa de modelagem
-- própria — não de mais uma linha aqui. Mesmo caso do crédito de diana no mutirão T1.
-- Composição: 28 tokens (co-execução E2, metade da recompensa de 56) + 150 de bônus.
INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                        missao_id, contraparte_carteira_id, chave_idempotencia,
                        saldo_apos_brl, saldo_apos_tokens, criado_em)
VALUES
    ('ffffffff-0000-0000-0000-000000000002',
     'eeeeeeee-0000-0000-0000-000000000003',
     'CREDITO', 'RECOMPENSA_MISSAO', 0.00, 28,
     'dddddddd-0000-0000-0000-000000000002', NULL,
     'seed-idem-bob-e2-tk',
     0.00, 28,
     NOW() - INTERVAL '2 days'),

    ('ffffffff-0000-0000-0000-000000000003',
     'eeeeeeee-0000-0000-0000-000000000003',
     'CREDITO', 'BONUS', 0.00, 150,
     NULL, NULL,
     'seed-idem-bob-bonus-tokens',
     0.00, 178,
     NOW() - INTERVAL '1 day');

-- carol (carteira eeeeeeee-0000-0000-0000-000000000004): saldo_brl=0, tokens=200
-- Missões C1 + C2 concluídas: 2 × COLETA de 100 tokens cada
INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                        missao_id, contraparte_carteira_id, chave_idempotencia,
                        saldo_apos_brl, saldo_apos_tokens, criado_em)
VALUES
    ('ffffffff-0000-0000-0000-000000000004',
     'eeeeeeee-0000-0000-0000-000000000004',
     'CREDITO', 'RECOMPENSA_MISSAO', 0.00, 35,
     'dddddddd-0000-0000-0000-000000000005', NULL,
     'seed-idem-carol-c1-tokens',
     0.00, 35,
     NOW() - INTERVAL '1 day'),

    ('ffffffff-0000-0000-0000-000000000005',
     'eeeeeeee-0000-0000-0000-000000000004',
     'CREDITO', 'RECOMPENSA_MISSAO', 0.00, 37,
     'dddddddd-0000-0000-0000-000000000006', NULL,
     'seed-idem-carol-c2-tokens',
     0.00, 72,
     NOW() - INTERVAL '12 hours');

-- diana (carteira eeeeeeee-0000-0000-0000-000000000005): tokens=99
--
-- Composição: 80 pela missão A1 (armário), que diana de fato executou, mais 19 pelo mutirão T1.
-- ATENÇÃO — no T1 o executor é erik, e a missão paga 38. Os 19 de diana são CO-PARTICIPAÇÃO no
-- mutirão: ficção deliberada do seed, pela mesma razão do bloco de bob acima. Mutirão com vários
-- participantes remunerados é plausível no domínio, mas o schema tem um único executor_id e
-- tokens_recompensa único, então não há como representá-lo — nem o valor por participante.
-- Não "corrija" ajustando o número: a correção de verdade é modelar co-participação.
INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                        missao_id, contraparte_carteira_id, chave_idempotencia,
                        saldo_apos_brl, saldo_apos_tokens, criado_em)
VALUES
    ('ffffffff-0000-0000-0000-000000000006',
     'eeeeeeee-0000-0000-0000-000000000005',
     'CREDITO', 'RECOMPENSA_MISSAO', 0.00, 80,
     'dddddddd-0000-0000-0000-000000000011', NULL,
     'seed-idem-diana-a1-tk',
     0.00, 80,
     NOW() - INTERVAL '5 days'),

    ('ffffffff-0000-0000-0000-000000000007',
     'eeeeeeee-0000-0000-0000-000000000005',
     'CREDITO', 'RECOMPENSA_MISSAO', 0.00, 19,
     'dddddddd-0000-0000-0000-000000000008', NULL,
     'seed-idem-diana-t1-tokens',
     0.00, 99,
     NOW() - INTERVAL '4 days');

-- erik (carteira eeeeeeee-0000-0000-0000-000000000006): saldo_brl=0, tokens=50
-- Missão T1 concluída: TRIBO 50 tokens
INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                        missao_id, contraparte_carteira_id, chave_idempotencia,
                        saldo_apos_brl, saldo_apos_tokens, criado_em)
VALUES
    ('ffffffff-0000-0000-0000-000000000008',
     'eeeeeeee-0000-0000-0000-000000000006',
     'CREDITO', 'RECOMPENSA_MISSAO', 0.00, 38,
     'dddddddd-0000-0000-0000-000000000008', NULL,
     'seed-idem-erik-t1-tokens',
     0.00, 38,
     NOW() - INTERVAL '4 days');

-- admin: saldo zerado, sem lançamentos
