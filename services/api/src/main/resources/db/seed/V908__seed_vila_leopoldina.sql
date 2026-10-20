-- =============================================================================
-- V908 — Seed do estande: Vila Leopoldina, em volta do Espaço Arca (FIAP NEXT).
-- Carregado APENAS nos perfis dev e test.
--
-- Só dado ESTÁTICO: tribo, pontos de custódia, vizinhos com saldo, parceiros e
-- benefícios. As MISSÕES não entram aqui — quem as cria é tools/feira/povoar.sh,
-- pela API, para que a recompensa saia da CalculadoraDeRecompensa e o pote saia
-- de um financiamento de verdade. Número de recompensa escrito à mão é o que a
-- V903 avisa para não fazer: a prévia diria um valor e a lista, outro.
--
-- Coordenadas geocodificadas no OpenStreetMap/Nominatim; o Arca foi conferido no
-- mapa do Google. Toda âncora nova está a MAIS de 3,5 km das âncoras de
-- Pinheiros/Vila Madalena (medido): o alerta de entrega falida vai para tribos a
-- até 3 km, e os testes que contam destinatários não podem ganhar um vizinho novo.
--
-- Faixa 98x nos UUIDs; faixa 908 no da tribo. Senha de todos: Senha@123.
-- =============================================================================

INSERT INTO tribo (id, nome, bairro, criada_em) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000908', 'Tribo Vila Leopoldina', 'Vila Leopoldina', NOW());

-- -----------------------------------------------------------------------
-- O renan, que demonstra no celular, passa a morar aqui — com os dois
-- consentimentos que o fan-out exige (sem NOTIFICACAO+LOCALIZACAO vigentes o
-- aviso da entrega falida não chega à aba Avisos). Nenhum teste referencia o
-- renan; as missões e o ledger dele na Cidade Líder continuam válidos, porque
-- tribo é pertencimento, não chave de lançamento.
-- -----------------------------------------------------------------------
UPDATE usuario SET tribo_id = 'aaaaaaaa-0000-0000-0000-000000000908', atualizado_em = NOW()
 WHERE id = 'bbbbbbbb-0000-0000-0000-000000000901';

INSERT INTO consentimento (id, usuario_id, tipo, concedido, versao_texto, ip, criado_em) VALUES
    ('dddddddd-0908-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000901',
     'TERMOS',      TRUE, '2026-08-01', NULL, NOW() - INTERVAL '30 days'),
    ('dddddddd-0908-0000-0000-000000000002', 'bbbbbbbb-0000-0000-0000-000000000901',
     'LOCALIZACAO', TRUE, '2026-08-01', NULL, NOW() - INTERVAL '30 days'),
    ('dddddddd-0908-0000-0000-000000000003', 'bbbbbbbb-0000-0000-0000-000000000901',
     'NOTIFICACAO', TRUE, '2026-08-01', NULL, NOW() - INTERVAL '30 days');

-- -----------------------------------------------------------------------
-- Vizinhos: quem cria e quem financia as missões comunitárias do povoar.sh.
-- Quem cria não financia a própria missão (ADR 0037), então são três.
-- `nivel` = 1 + floor(sqrt(xp/100)): 320 → 2, 180 → 2, 60 → 1.
-- -----------------------------------------------------------------------
INSERT INTO usuario (id, nome, email, senha_hash, handle, tribo_id, xp, nivel, streak, rating,
                     papel, status, criado_em, atualizado_em, versao) VALUES
    ('bbbbbbbb-0000-0000-0000-000000000981', 'Paula Andrade', 'paula@omnitribo.dev',
     '{bcrypt}' || crypt('Senha@123', gen_salt('bf', 10)), 'paula',
     'aaaaaaaa-0000-0000-0000-000000000908',
     320, 2, 5, 4.8, 'USUARIO', 'ATIVO', NOW() - INTERVAL '50 days', NOW(), 0),
    ('bbbbbbbb-0000-0000-0000-000000000982', 'Sérgio Tanaka', 'sergio@omnitribo.dev',
     '{bcrypt}' || crypt('Senha@123', gen_salt('bf', 10)), 'sergio',
     'aaaaaaaa-0000-0000-0000-000000000908',
     180, 2, 3, 4.7, 'USUARIO', 'ATIVO', NOW() - INTERVAL '40 days', NOW(), 0),
    ('bbbbbbbb-0000-0000-0000-000000000983', 'Beatriz Rocha', 'beatriz@omnitribo.dev',
     '{bcrypt}' || crypt('Senha@123', gen_salt('bf', 10)), 'beatriz',
     'aaaaaaaa-0000-0000-0000-000000000908',
     60, 1, 1, 4.9, 'USUARIO', 'ATIVO', NOW() - INTERVAL '20 days', NOW(), 0);

-- Carteira e lançamento de abertura SEMPRE juntos: MigracaoTest varre toda carteira e exige
-- SUM(ledger) = saldo. BONUS é o bootstrap de um banco sem histórico.
INSERT INTO carteira (id, usuario_id, saldo_brl, saldo_tokens, versao) VALUES
    ('eeeeeeee-0000-0000-0000-000000000981', 'bbbbbbbb-0000-0000-0000-000000000981', 0.00, 400, 1),
    ('eeeeeeee-0000-0000-0000-000000000982', 'bbbbbbbb-0000-0000-0000-000000000982', 0.00, 400, 1),
    ('eeeeeeee-0000-0000-0000-000000000983', 'bbbbbbbb-0000-0000-0000-000000000983', 0.00, 200, 1);

INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                        missao_id, contraparte_carteira_id, chave_idempotencia,
                        saldo_apos_brl, saldo_apos_tokens, criado_em) VALUES
    ('ffffffff-0000-0000-0000-000000000981', 'eeeeeeee-0000-0000-0000-000000000981',
     'CREDITO', 'BONUS', 0.00, 400, NULL, NULL, 'seed-vl-paula-bonus', 0.00, 400,
     NOW() - INTERVAL '30 days'),
    ('ffffffff-0000-0000-0000-000000000982', 'eeeeeeee-0000-0000-0000-000000000982',
     'CREDITO', 'BONUS', 0.00, 400, NULL, NULL, 'seed-vl-sergio-bonus', 0.00, 400,
     NOW() - INTERVAL '30 days'),
    ('ffffffff-0000-0000-0000-000000000983', 'eeeeeeee-0000-0000-0000-000000000983',
     'CREDITO', 'BONUS', 0.00, 200, NULL, NULL, 'seed-vl-beatriz-bonus', 0.00, 200,
     NOW() - INTERVAL '30 days');

-- -----------------------------------------------------------------------
-- Pontos de custódia, vazios: MigracaoTest exige `ocupacao` igual ao que está
-- fisicamente no ponto, e nenhuma encomenda foi semeada aqui.
--
-- O ESTANDE é o ponto onde a missão da demonstração nasce. A coordenada abaixo é a
-- do Arca; no dia, tools/demo/ponto-aqui.sh a move para onde o celular está — o
-- check-in exige o aparelho perto da origem.
-- -----------------------------------------------------------------------
INSERT INTO ponto_custodia (id, codigo, tipo, apelido, ponto, tribo_id, capacidade, ocupacao,
                            ativo, criado_em) VALUES
    ('cccccccc-0000-0000-0000-000000000981', 'LM-VLB-001', 'LOJA', 'Leroy Merlin Villa Lobos',
     ST_SetSRID(ST_MakePoint(-46.7370165, -23.5458549), 4326)::geography,
     'aaaaaaaa-0000-0000-0000-000000000908', 60, 0, TRUE, NOW()),
    ('cccccccc-0000-0000-0000-000000000982', 'EST-ARCA-01', 'LOCKER',
     'Estande Omni-Tribo · Espaço Arca',
     ST_SetSRID(ST_MakePoint(-46.7341000, -23.5402000), 4326)::geography,
     'aaaaaaaa-0000-0000-0000-000000000908', 200, 0, TRUE, NOW()),
    ('cccccccc-0000-0000-0000-000000000983', 'PT-VLE-001', 'PORTARIA',
     'Portaria Cond. Parque Leopoldina',
     ST_SetSRID(ST_MakePoint(-46.7245745, -23.5342365), 4326)::geography,
     'aaaaaaaa-0000-0000-0000-000000000908', 12, 0, TRUE, NOW());

-- -----------------------------------------------------------------------
-- Parceiros e benefícios do bairro — onde o token vira coisa e sai de circulação.
-- Em BEM ou PERCENTUAL, nunca em reais (ck_beneficio_sem_reais, ADR 0027).
-- -----------------------------------------------------------------------
INSERT INTO parceiro (id, nome, ponto, tribo_id, cep, logradouro, bairro, cidade, uf, ativo,
                      criado_em) VALUES
    ('22222222-0000-0000-0000-000000000981', 'Padaria Flor da Leopoldina',
     ST_SetSRID(ST_MakePoint(-46.7292628, -23.5309775), 4326)::geography,
     'aaaaaaaa-0000-0000-0000-000000000908',
     '05302001', 'Rua Schilling', 'Vila Leopoldina', 'São Paulo', 'SP', TRUE, NOW()),
    ('22222222-0000-0000-0000-000000000982', 'Bicicletaria do Parque',
     ST_SetSRID(ST_MakePoint(-46.7241046, -23.5441098), 4326)::geography,
     'aaaaaaaa-0000-0000-0000-000000000908',
     '05461010', 'Avenida Professor Fonseca Rodrigues', 'Alto de Pinheiros', 'São Paulo', 'SP',
     TRUE, NOW()),
    ('22222222-0000-0000-0000-000000000983', 'Hortifrúti da Gastão',
     ST_SetSRID(ST_MakePoint(-46.7298747, -23.5396563), 4326)::geography,
     'aaaaaaaa-0000-0000-0000-000000000908',
     '05319000', 'Avenida Doutor Gastão Vidigal', 'Vila Leopoldina', 'São Paulo', 'SP',
     TRUE, NOW());

INSERT INTO beneficio (id, parceiro_id, titulo, descricao, custo_tokens, tipo, ativo, criado_em)
VALUES
    ('33333333-0000-0000-0000-000000000981', '22222222-0000-0000-0000-000000000981',
     'Um café coado e um pão na chapa',
     'Retire no balcão apresentando o código de retirada.', 15, 'BEM', TRUE, NOW()),
    ('33333333-0000-0000-0000-000000000982', '22222222-0000-0000-0000-000000000981',
     'Uma fornada de pão de queijo (300 g)',
     'Saída das 16h. Combine no balcão no dia anterior.', 25, 'BEM', TRUE, NOW()),
    ('33333333-0000-0000-0000-000000000983', '22222222-0000-0000-0000-000000000982',
     'Um remendo de câmara de ar',
     'Serviço rápido, feito na hora, enquanto você espera.', 10, 'BEM', TRUE, NOW()),
    ('33333333-0000-0000-0000-000000000984', '22222222-0000-0000-0000-000000000982',
     '20% de desconto na revisão da bicicleta',
     'Desconto sobre a mão de obra da revisão completa.', 40, 'PERCENTUAL', TRUE, NOW()),
    ('33333333-0000-0000-0000-000000000985', '22222222-0000-0000-0000-000000000983',
     '15% de desconto na feira da semana',
     'Sobre frutas, legumes e verduras. Apresente o código antes do caixa.', 30, 'PERCENTUAL',
     TRUE, NOW());

-- -----------------------------------------------------------------------
-- Aporte extra no patrocinador de `transportadora-dev`: um dia inteiro de estande
-- converte dezenas de entregas, e saldo curto faria o caminho feliz virar
-- SEM_PATROCINIO na frente de alguém. É o mesmo evento que em produção entra pelo
-- endpoint ADMIN de aporte — a ÚNICA ponta que emite token.
-- -----------------------------------------------------------------------
INSERT INTO lancamento (id, carteira_id, sinal, motivo, valor_brl, valor_tokens,
                        missao_id, contraparte_carteira_id, chave_idempotencia,
                        saldo_apos_brl, saldo_apos_tokens, criado_em)
SELECT 'ffffffff-0000-0000-0000-000000000990', c.id, 'CREDITO', 'APORTE_PATROCINADOR', 0.00,
       10000, NULL, NULL, 'seed-aporte-feira-transportadora-dev', c.saldo_brl,
       c.saldo_tokens + 10000, NOW()
  FROM carteira c
 WHERE c.id = 'eeeeeeee-0000-0000-0000-000000000950';

UPDATE carteira SET saldo_tokens = saldo_tokens + 10000, versao = versao + 1
 WHERE id = 'eeeeeeee-0000-0000-0000-000000000950';
