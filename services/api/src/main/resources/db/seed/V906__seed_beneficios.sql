-- =============================================================================
-- V906 — Parceiros e benefícios da Cidade Líder (perfis dev e test)
--
-- Sem isto a tela de benefícios abre vazia na demonstração, e o sumidouro do token
-- fica sem catálogo para exercitar. Os parceiros ficam no bairro da V903 — Tribo
-- Cidade Líder, zona leste — e perto das coordenadas que aquele seed já usa, para
-- que a busca por raio devolva resultado sem ninguém precisar procurar onde clicar.
--
-- UUIDs seguem a convenção de um PREFIXO POR TABELA que os seeds anteriores criaram
-- (aaaaaaaa=tribo, bbbbbbbb=usuario, cccccccc=ponto_custodia, dddddddd=missao,
-- eeeeeeee=carteira, ffffffff=lancamento, 77777777=patrocinador,
-- 88888888=entrega_falida): parceiro fica com 22222222 e beneficio com 33333333,
-- dois prefixos até aqui livres. O sufixo …960+ evita as faixas 900-908 e 950-951,
-- já ocupadas.
--
-- NENHUM benefício se anuncia em reais. Não é estilo: ck_beneficio_sem_reais (V24)
-- reprova, e um seed que violasse a própria regra derrubaria o boot de dev e test.
-- Ver ADR 0009 §6.
-- =============================================================================

INSERT INTO parceiro (id, nome, ponto, tribo_id, cep, logradouro, bairro, cidade, uf, ativo, criado_em) VALUES
    -- ~200 m do ponto de referência da V903: é o primeiro a aparecer na busca por raio.
    ('22222222-0000-0000-0000-000000000960',
     'Padaria Pão da Praça',
     ST_SetSRID(ST_MakePoint(-46.50650, -23.57280), 4326)::geography,
     'aaaaaaaa-0000-0000-0000-000000000901',
     '08285000', 'Rua Médio Iguaçu', 'Cidade Líder', 'São Paulo', 'SP', TRUE, NOW()),

    ('22222222-0000-0000-0000-000000000961',
     'Bicicletaria do Zé',
     ST_SetSRID(ST_MakePoint(-46.50480, -23.57110), 4326)::geography,
     'aaaaaaaa-0000-0000-0000-000000000901',
     '08285100', 'Rua Sapé do Norte', 'Cidade Líder', 'São Paulo', 'SP', TRUE, NOW()),

    -- Perto do segundo ponto de custódia da V903, uns 4 km ao norte do primeiro:
    -- serve para provar que o raio de fato RECORTA, e não devolve tudo.
    ('22222222-0000-0000-0000-000000000962',
     'Mercearia Dona Neusa',
     ST_SetSRID(ST_MakePoint(-46.46870, -23.55670), 4326)::geography,
     'aaaaaaaa-0000-0000-0000-000000000901',
     '08021000', 'Avenida São Miguel', 'Vila Nova Curuçá', 'São Paulo', 'SP', TRUE, NOW()),

    -- INATIVO de propósito: é a fixture de "parceiro desligado não aparece no
    -- catálogo". Sem ele, testar essa regra exigiria desativar um parceiro no meio
    -- do teste — estado compartilhado que dependeria da ordem de execução, porque o
    -- contêiner não é truncado entre classes.
    ('22222222-0000-0000-0000-000000000963',
     'Sebo da Esquina (fechado)',
     ST_SetSRID(ST_MakePoint(-46.50600, -23.57200), 4326)::geography,
     'aaaaaaaa-0000-0000-0000-000000000901',
     '08285200', 'Rua Forte do Rio Branco', 'Cidade Líder', 'São Paulo', 'SP', FALSE, NOW());

-- -----------------------------------------------------------------------
-- Benefícios. Custos baixos de propósito: os saldos semeados vão de 38 a 178
-- tokens, e um catálogo que ninguém consegue pagar não demonstra sumidouro nenhum.
-- -----------------------------------------------------------------------
INSERT INTO beneficio (id, parceiro_id, titulo, descricao, custo_tokens, tipo, ativo, criado_em) VALUES
    ('33333333-0000-0000-0000-000000000960',
     '22222222-0000-0000-0000-000000000960',
     'Um café coado e um pão na chapa',
     'Retire no balcão apresentando o código. Válido de segunda a sábado, até as 11h.',
     15, 'BEM', TRUE, NOW()),

    ('33333333-0000-0000-0000-000000000961',
     '22222222-0000-0000-0000-000000000960',
     'Uma fornada de pão francês (500 g)',
     'Retire quente, às 16h. Combine no balcão no dia anterior.',
     25, 'BEM', TRUE, NOW()),

    ('33333333-0000-0000-0000-000000000962',
     '22222222-0000-0000-0000-000000000961',
     '20% de desconto na revisão da bicicleta',
     'Desconto proporcional sobre a mão de obra da revisão completa. Não acumula com outras ofertas.',
     40, 'PERCENTUAL', TRUE, NOW()),

    ('33333333-0000-0000-0000-000000000963',
     '22222222-0000-0000-0000-000000000961',
     'Um remendo de câmara de ar',
     'Serviço rápido, feito na hora, enquanto você espera.',
     10, 'BEM', TRUE, NOW()),

    ('33333333-0000-0000-0000-000000000964',
     '22222222-0000-0000-0000-000000000962',
     '15% de desconto na feira da semana',
     'Aplicado sobre hortifrúti, às quartas-feiras. Apresente o código antes de passar no caixa.',
     30, 'PERCENTUAL', TRUE, NOW()),

    -- Benefício INATIVO num parceiro ATIVO: a outra metade da fixture de catálogo.
    -- Desativar o parceiro e desativar o benefício são dois caminhos diferentes
    -- para sumir da vitrine, e os dois precisam de teste.
    ('33333333-0000-0000-0000-000000000965',
     '22222222-0000-0000-0000-000000000960',
     'Panetone artesanal (fora de época)',
     'Disponível apenas em dezembro. Mantido no catálogo como registro.',
     50, 'BEM', FALSE, NOW()),

    -- Benefício de parceiro INATIVO: não pode aparecer nem ser resgatado, mesmo
    -- estando ele próprio marcado como ativo.
    ('33333333-0000-0000-0000-000000000966',
     '22222222-0000-0000-0000-000000000963',
     'Um livro usado à sua escolha',
     'A loja encerrou as atividades; o registro fica para histórico.',
     20, 'BEM', TRUE, NOW());
