-- =============================================================================
-- V15 — Recompensa de missão deixa de ser em BRL
--
-- POR QUÊ: o modelo de produto foi corrigido. A premissa antiga era que o CRIADOR
-- pagava a missão em dinheiro, e o ck_missao_economia da V3 refletia isso ao proibir
-- valor_brl só em TRIBO/COLETA. Mas no Omni-Tribo quem cria a missão NÃO paga: a
-- recompensa é reputação (XP) e moeda comunitária (TOKEN), resgatável em benefícios
-- de parceiros do bairro. Ver ADR 0009, que substitui a tabela de moedas do ADR 0004.
--
-- CONSEQUÊNCIA MEDIDA, e o motivo desta migration existir: enquanto valor_brl era
-- recompensa, a conclusão creditava esse valor na carteira do executor SEM débito
-- correspondente em lugar nenhum — não havia escrow no publicar, e o único DEBITO de
-- BRL do sistema é o saque. Dois usuários combinados criavam, aceitavam, faziam
-- check-in e confirmavam missões para gerar saldo indefinidamente. Medido contra a API
-- em execução: R$ 118,00 viraram R$ 1.618,00 em três ciclos, com o saldo do criador
-- intacto. A reconciliação respondia integro=true o tempo todo, e corretamente — ela
-- compara ledger com projeção, não verifica conservação, e o BRL não tinha invariante
-- de conservação para violar.
--
-- O TOKEN não tem esse problema porque tem circuito fechado: membros financiam o pote
-- debitando a própria carteira, a conclusão paga DO pote, e cancelar ou expirar estorna.
-- Invariante SUM(carteira.saldo_tokens) + SUM(missao.pote_tokens) constante, medida de
-- ponta a ponta: 500 antes, 500 depois.
--
-- O QUE NÃO MUDA: as colunas valor_brl, carteira.saldo_brl e todo o SaqueService
-- continuam existindo, testados e intactos. São a infraestrutura da conversão
-- patrocinada de TOKEN para moeda corrente, se o patrocínio se concretizar. Remover
-- schema por decisão de produto que pode voltar é trabalho perdido nas duas direções.
-- =============================================================================

-- Zera dados pré-existentes antes de apertar a constraint. Em base nova é no-op; numa
-- base de dev antiga é o que permite a migration passar em vez de falhar na validação.
UPDATE missao SET valor_brl = 0 WHERE valor_brl <> 0;

ALTER TABLE missao DROP CONSTRAINT ck_missao_economia;

-- A regra deixa de depender da categoria: NENHUMA missão remunera em BRL nesta etapa.
-- Mantida como CHECK, e não apenas como validação de request, porque é invariante de
-- domínio — precisa valer para qualquer caminho de escrita, inclusive seed e correção
-- manual em produção.
ALTER TABLE missao ADD CONSTRAINT ck_missao_economia CHECK (valor_brl = 0);
