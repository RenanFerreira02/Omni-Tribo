-- =============================================================================
-- V904 — Fixtures do "Fim da Entrega Falida" (perfis dev e test)
--
-- O seed anterior não conseguia exercitar dois caminhos do módulo, e os dois são
-- justamente os que o produto precisa defender:
--
--   1. PONTO LOTADO. Nenhum dos cinco pontos da V900 está em capacidade máxima
--      (50/3, 12/2, 5/1, 3/0, 10/4), então "encomenda recusada por falta de vaga"
--      não tinha fixture nenhuma. Testar isso enchendo um ponto existente a partir
--      do teste deixaria o estado dependente da ORDEM de execução — o container
--      não é truncado entre testes.
--
--   2. NOTIFICAÇÃO ENVIADA. A V902 deixou a Alice com NOTIFICACAO REVOGADA e o Bob
--      sem nenhuma decisão. Excelente para provar que não notificamos quem recusou
--      e quem nunca escolheu; inútil para provar que notificamos quem aceitou —
--      não existia uma única pessoa no seed com os dois consentimentos vigentes.
--
-- Faixa 900+ pela razão de sempre: garante por construção que roda depois de todo
-- o schema. Ver o cabeçalho da V900 e a seção Banco do CLAUDE.md.
-- =============================================================================

-- -----------------------------------------------------------------------
-- 1. Ponto de custódia LOTADO — capacidade 2, ocupação 2.
--
-- Fica na Tribo Pinheiros, a poucas centenas de metros do Leroy Merlin, para que
-- o mesmo raio de alerta alcance os dois: assim o teste de recusa e o de
-- notificação usam a mesma vizinhança e uma diferença de resultado não pode ser
-- explicada por geografia.
-- -----------------------------------------------------------------------
INSERT INTO ponto_custodia (id, codigo, tipo, apelido, ponto, tribo_id,
                            capacidade, ocupacao, ativo, criado_em) VALUES
    ('cccccccc-0000-0000-0000-000000000904',
     'PT-PIN-904', 'PORTARIA',
     'Portaria Ed. Aurora (lotada)',
     ST_SetSRID(ST_MakePoint(-46.6950, -23.5655), 4326)::geography,
     'aaaaaaaa-0000-0000-0000-000000000001',
     2, 2, TRUE, NOW());

-- As duas encomendas que ocupam as duas vagas. Sem elas, ocupacao = 2 seria uma
-- mentira que MigracaoTest reprova: a invariante exige que a ocupação de cada ponto
-- seja igual às encomendas fisicamente lá (pendentes + convertidas cuja missão não
-- concluiu, excluídas as recusadas).
INSERT INTO entrega_falida (id, transportadora, codigo_rastreio, motivo, ponto_custodia_id,
                            missao_id, recebido_em, assinatura_verificada, convertida_em,
                            peso_kg, volume_l, destino_cep, destino_logradouro,
                            destino_bairro, destino_cidade, destino_uf) VALUES
    ('11111111-0000-0000-0000-000000000904',
     'Transportadora Teste', 'TT904000000001',
     'Destinatário ausente; encomenda retida na portaria.',
     'cccccccc-0000-0000-0000-000000000904',
     NULL, NOW() - INTERVAL '2 days', TRUE, NULL,
     8.50, 30.00, '05416000', 'Rua Teodoro Sampaio', 'Pinheiros', 'São Paulo', 'SP'),

    ('11111111-0000-0000-0000-000000000905',
     'Transportadora Teste', 'TT904000000002',
     'Acesso ao condomínio negado ao entregador.',
     'cccccccc-0000-0000-0000-000000000904',
     NULL, NOW() - INTERVAL '1 day', TRUE, NULL,
     22.00, 60.00, '05416000', 'Rua Teodoro Sampaio', 'Pinheiros', 'São Paulo', 'SP');

-- -----------------------------------------------------------------------
-- 2. Fernanda — a única pessoa do seed que PODE ser notificada.
--
-- Tribo Pinheiros (para cair no raio dos pontos acima), xp 400 → nível 3 pela curva
-- de RegraNivel, portanto acima do nivel-minimo 2 exigido por missão de entrega
-- falida. Os dois requisitos precisam coexistir na MESMA pessoa: notificar alguém
-- que depois levaria 422 ao tentar aceitar seria anunciar o que não se pode entregar.
-- -----------------------------------------------------------------------
INSERT INTO usuario (id, nome, email, senha_hash, handle, tribo_id, xp, nivel,
                     streak, rating, papel, status, criado_em, atualizado_em, versao) VALUES
    ('bbbbbbbb-0000-0000-0000-000000000904',
     'Fernanda Lima',
     'fernanda@omnitribo.dev',
     '{bcrypt}' || crypt('Senha@123', gen_salt('bf', 10)),
     'fernanda',
     'aaaaaaaa-0000-0000-0000-000000000001',
     400, 3, 4, 4.9, 'USUARIO', 'ATIVO', NOW(), NOW(), 0);

INSERT INTO carteira (id, usuario_id, saldo_brl, saldo_tokens, versao) VALUES
    ('eeeeeeee-0000-0000-0000-000000000904',
     'bbbbbbbb-0000-0000-0000-000000000904', 0.00, 0, 0);

-- Os DOIS consentimentos vigentes. NOTIFICACAO porque é uma notificação;
-- LOCALIZACAO porque a decisão de enviá-la usou a posição da tribo dela.
-- Concedidos em datas distintas de propósito: a consulta resolve o estado atual por
-- DISTINCT ON (tipo) ORDER BY criado_em DESC, e datas iguais tornariam o teste cego
-- a um erro de ordenação.
INSERT INTO consentimento (id, usuario_id, tipo, concedido, versao_texto, ip, criado_em) VALUES
    ('dddddddd-0904-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000904',
     'TERMOS',      TRUE, '2026-08-01', NULL, NOW() - INTERVAL '40 days'),
    ('dddddddd-0904-0000-0000-000000000002', 'bbbbbbbb-0000-0000-0000-000000000904',
     'LOCALIZACAO', TRUE, '2026-08-01', NULL, NOW() - INTERVAL '40 days'),
    ('dddddddd-0904-0000-0000-000000000003', 'bbbbbbbb-0000-0000-0000-000000000904',
     'NOTIFICACAO', TRUE, '2026-08-01', NULL, NOW() - INTERVAL '35 days');

-- -----------------------------------------------------------------------
-- 3. Gustavo — consentiu tudo, mas NÃO tem reputação.
--
-- xp 0 → nível 1, abaixo do mínimo. É o par exato da Fernanda: mesma tribo, mesmos
-- consentimentos, e a ÚNICA variável diferente é o nível. Sem ele, um teste que
-- verifique "não notifica quem não pode aceitar" poderia passar por acidente — pela
-- tribo errada, pelo consentimento faltando, por qualquer coisa menos a regra em
-- avaliação.
-- -----------------------------------------------------------------------
INSERT INTO usuario (id, nome, email, senha_hash, handle, tribo_id, xp, nivel,
                     streak, rating, papel, status, criado_em, atualizado_em, versao) VALUES
    ('bbbbbbbb-0000-0000-0000-000000000905',
     'Gustavo Nunes',
     'gustavo@omnitribo.dev',
     '{bcrypt}' || crypt('Senha@123', gen_salt('bf', 10)),
     'gustavo',
     'aaaaaaaa-0000-0000-0000-000000000001',
     0, 1, 0, 0.0, 'USUARIO', 'ATIVO', NOW(), NOW(), 0);

INSERT INTO carteira (id, usuario_id, saldo_brl, saldo_tokens, versao) VALUES
    ('eeeeeeee-0000-0000-0000-000000000905',
     'bbbbbbbb-0000-0000-0000-000000000905', 0.00, 0, 0);

INSERT INTO consentimento (id, usuario_id, tipo, concedido, versao_texto, ip, criado_em) VALUES
    ('dddddddd-0905-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000905',
     'TERMOS',      TRUE, '2026-08-01', NULL, NOW() - INTERVAL '10 days'),
    ('dddddddd-0905-0000-0000-000000000002', 'bbbbbbbb-0000-0000-0000-000000000905',
     'LOCALIZACAO', TRUE, '2026-08-01', NULL, NOW() - INTERVAL '10 days'),
    ('dddddddd-0905-0000-0000-000000000003', 'bbbbbbbb-0000-0000-0000-000000000905',
     'NOTIFICACAO', TRUE, '2026-08-01', NULL, NOW() - INTERVAL '10 days');
