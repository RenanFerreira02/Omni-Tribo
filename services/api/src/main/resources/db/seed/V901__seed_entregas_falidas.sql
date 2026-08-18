-- =============================================================================
-- V901 — Seed de entregas falidas (perfis dev e test)
--
-- POR QUE ESTE ARQUIVO EXISTE: a tese do produto é "uma entrega que falhou vira
-- missão comunitária remunerada", e o seed não tinha NENHUMA entrega falida. As 4
-- missões ENTREGA da V900 já apontavam para pontos de custódia, mas sem a linha que
-- explica de onde a encomenda veio — a metade logística da história ficava sem dado,
-- justamente a que dá nome ao challenge.
--
-- Faixa 900+ pelo mesmo motivo da V900: garante por construção que o seed roda depois
-- de todo o schema. Ver o cabeçalho da V900 e a seção Banco do CLAUDE.md.
--
-- DUAS POPULAÇÕES, de propósito:
--   * CONVERTIDA  (missao_id e convertida_em preenchidos) — já virou missão. É o
--     "depois" da tese.
--   * PENDENTE    (missao_id e convertida_em nulos) — encomenda parada no ponto de
--     custódia esperando alguém criar a missão de retirada. É o "antes", e é o estado
--     que a tela de oportunidades do app vai consumir. Sem estas linhas, o fluxo só
--     poderia ser demonstrado a partir de dados criados à mão.
--
-- COERÊNCIA COM ponto_custodia.ocupacao: a ocupação de cada ponto passa a ser
-- exatamente o número de encomendas FISICAMENTE lá — pendentes, mais as convertidas
-- cuja missão ainda não foi concluída. Encomenda de missão CONCLUIDA já saiu da
-- custódia e não conta. Confere assim:
--   LM-PIN-001 (3) = 1 convertida ABERTA + 2 pendentes
--   LK-CON-001 (2) = 0 (convertida já CONCLUIDA) + 2 pendentes
--   PT-PIN-001 (1) = 0 (convertida já CONCLUIDA) + 1 pendente
--   LK-VMA-001 (4) = 1 convertida ABERTA + 3 pendentes
--   VZ-VMA-001 (0) = nenhuma
-- Nenhum código mantém esse contador hoje (o módulo logistica não tem serviço); a
-- coerência aqui é para o seed não nascer mentindo, no mesmo espírito da regra que
-- exige saldo sustentado por ledger.
--
-- missao_id é UUID puro, sem FK — fronteira logistica→missoes, deliberada (ver V6).
-- =============================================================================

INSERT INTO entrega_falida (id, transportadora, codigo_rastreio, motivo, ponto_custodia_id,
                            missao_id, recebido_em, assinatura_verificada, convertida_em) VALUES

    -- ---------------------------------------------------------------------
    -- CONVERTIDAS — deram origem às 4 missões ENTREGA da V900
    -- ---------------------------------------------------------------------
    ('11111111-0000-0000-0000-000000000001', 'Correios', 'BR847291035LM',
     'Destinatário ausente após 3 tentativas de entrega',
     'cccccccc-0000-0000-0000-000000000002',            -- LOCKER Consolação
     'dddddddd-0000-0000-0000-000000000001',            -- tinta acrílica 18L (CONCLUIDA)
     NOW() - INTERVAL '9 days', TRUE, NOW() - INTERVAL '8 days'),

    ('11111111-0000-0000-0000-000000000002', 'Jadlog', 'JD00449182773',
     'Volume acima do limite de manuseio do entregador — 28 kg',
     'cccccccc-0000-0000-0000-000000000003',            -- Portaria Ed. Solar Pinheiros
     'dddddddd-0000-0000-0000-000000000002',            -- porcelanato 60x60 (CONCLUIDA)
     NOW() - INTERVAL '7 days', TRUE, NOW() - INTERVAL '6 days'),

    ('11111111-0000-0000-0000-000000000003', 'Loggi', 'LG7731920045',
     'Endereço não localizado: número divergente na fachada',
     'cccccccc-0000-0000-0000-000000000001',            -- Leroy Merlin Pinheiros (LOJA)
     'dddddddd-0000-0000-0000-000000000003',            -- torneira + acessórios (ABERTA)
     NOW() - INTERVAL '3 days', TRUE, NOW() - INTERVAL '2 days'),

    ('11111111-0000-0000-0000-000000000004', 'Total Express', 'TE9920477310',
     'Recusa de recebimento: portaria sem autorização do morador',
     'cccccccc-0000-0000-0000-000000000005',            -- LOCKER Vila Madalena
     'dddddddd-0000-0000-0000-000000000004',            -- kit de luminárias (ABERTA)
     NOW() - INTERVAL '2 days', FALSE, NOW() - INTERVAL '1 day'),

    -- ---------------------------------------------------------------------
    -- PENDENTES — na custódia, ainda sem missão de retirada
    -- ---------------------------------------------------------------------
    ('11111111-0000-0000-0000-000000000005', 'Correios', 'BR118824460LM',
     'Destinatário ausente após 3 tentativas de entrega',
     'cccccccc-0000-0000-0000-000000000001',            -- Leroy Merlin Pinheiros
     NULL, NOW() - INTERVAL '4 days', TRUE, NULL),

    ('11111111-0000-0000-0000-000000000006', 'Jadlog', 'JD00512907441',
     'Estabelecimento fechado no horário da entrega',
     'cccccccc-0000-0000-0000-000000000001',            -- Leroy Merlin Pinheiros
     NULL, NOW() - INTERVAL '2 days', TRUE, NULL),

    ('11111111-0000-0000-0000-000000000007', 'Loggi', 'LG8840113277',
     'Caixa de piso laminado sem quem receba — 31 kg, entrega em 4º andar sem elevador',
     'cccccccc-0000-0000-0000-000000000002',            -- LOCKER Consolação
     NULL, NOW() - INTERVAL '5 days', TRUE, NULL),

    -- Assinatura NÃO verificada: o caso que a F10 vai precisar tratar como disputa,
    -- porque não há prova de quem recebeu a encomenda no ponto de custódia.
    ('11111111-0000-0000-0000-000000000008', 'Braspress', 'BP4471028890',
     'Divergência de assinatura no comprovante de entrega',
     'cccccccc-0000-0000-0000-000000000002',            -- LOCKER Consolação
     NULL, NOW() - INTERVAL '1 day', FALSE, NULL),

    ('11111111-0000-0000-0000-000000000009', 'Total Express', 'TE1102938477',
     'Destinatário mudou de endereço',
     'cccccccc-0000-0000-0000-000000000003',            -- Portaria Ed. Solar Pinheiros
     NULL, NOW() - INTERVAL '6 days', TRUE, NULL),

    ('11111111-0000-0000-0000-000000000010', 'Correios', 'BR330192845LM',
     'Destinatário ausente após 3 tentativas de entrega',
     'cccccccc-0000-0000-0000-000000000005',            -- LOCKER Vila Madalena
     NULL, NOW() - INTERVAL '3 days', TRUE, NULL),

    ('11111111-0000-0000-0000-000000000011', 'Jadlog', 'JD00587713002',
     'Kit de vaso sanitário frágil: transportadora recusou segunda tentativa',
     'cccccccc-0000-0000-0000-000000000005',            -- LOCKER Vila Madalena
     NULL, NOW() - INTERVAL '2 days', TRUE, NULL),

    ('11111111-0000-0000-0000-000000000012', 'Loggi', 'LG9018827344',
     'Acesso ao condomínio negado: entregador sem cadastro prévio',
     'cccccccc-0000-0000-0000-000000000005',            -- LOCKER Vila Madalena
     NULL, NOW() - INTERVAL '1 day', TRUE, NULL);
