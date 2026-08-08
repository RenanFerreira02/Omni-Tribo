-- =============================================================================
-- V16 — Recompensa calculada pelo servidor e congelada na criação
--
-- POR QUÊ: até aqui o CLIENTE escolhia a própria recompensa. `CriarMissaoRequest`
-- tinha xpRecompensa e tokensRecompensa, e o único controle era um @Max. Medido
-- contra a API em execução: uma missão AJUDA sem peso, sem volume e sem destino foi
-- criada com 5.000 XP e 1.000 tokens — exatamente o teto. Teto sem fórmula significa
-- que toda missão pode valer o teto, e a única variável é a vontade de quem cria.
--
-- CONSEQUÊNCIA MEDIDA: como pagaTokensDoPote cobre só TRIBO e COLETA, ENTREGA e AJUDA
-- cunham token. Somando as duas coisas — cliente escolhe o valor, e o valor é cunhado —
-- dois usuários combinados emitiram 656 → 2.656 tokens em dois ciclos do fluxo feliz,
-- com GET /admin/carteiras/reconciliacao respondendo integro=true o tempo todo. É o
-- mesmo vetor do valor_brl que o ADR 0009 fechou, transposto para a moeda que o próprio
-- ADR tornou central. Ver docs/auditoria/F5.md, item A.
--
-- ISTO NÃO FECHA A CUNHAGEM — fecha o arbítrio sobre o tamanho dela. O sumidouro de
-- ENTREGA/AJUDA é a carteira de patrocinador da F8.
-- =============================================================================

-- (1) Complexidade: multiplicador da fórmula.
--
-- Nulável porque as 12 missões do seed e qualquer base pré-existente não a têm. Não é
-- NOT NULL com default porque um default silencioso ("todas são LEVE") mentiria sobre
-- dado que nunca foi coletado — nulo diz "não sei", que é a verdade.
--
-- VARCHAR(7) casa com o maior valor ('PESADA' tem 6; a folga é a mesma disciplina do
-- resto do schema). O length do @Column em Missao tem de bater: ddl-auto é validate.
ALTER TABLE missao ADD COLUMN complexidade VARCHAR(7);
ALTER TABLE missao ADD CONSTRAINT ck_missao_complexidade
    CHECK (complexidade IS NULL OR complexidade IN ('LEVE', 'MEDIA', 'PESADA'));

-- (2) Versão da fórmula que produziu xp_recompensa e tokens_recompensa.
--
-- É o que torna a recompensa AUDITÁVEL, e não apenas congelada. Sem ela, mudar um
-- parâmetro de calibração amanhã reinterpreta retroativamente toda missão já aceita:
-- quebra o acordo com quem aceitou, e torna impossível responder "este crédito de 43
-- tokens estava certo quando foi feito?". Mesmo princípio do saldo_apos_* no ledger,
-- aplicado à ORIGEM do valor em vez de ao efeito dele.
--
-- Nulável pela mesma razão de (1): missão anterior a esta migration não foi calculada
-- por fórmula nenhuma, e fingir uma versão seria pior que admitir a ausência.
ALTER TABLE missao ADD COLUMN versao_formula INT;

-- (3) Multiplicador de risco — RESERVADO, sem uso nesta fase.
--
-- Entra agora, nulável, para que o congelamento da recompensa já nasça completo e a F11
-- não precise de uma segunda migration sobre a mesma tabela. Nenhum código lê ou escreve
-- esta coluna hoje; quem a preencher na F11 precisa congelá-la junto com versao_formula,
-- pelo mesmo motivo do item (2).
ALTER TABLE missao ADD COLUMN multiplicador_risco NUMERIC(4,2);
