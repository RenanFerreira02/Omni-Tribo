-- =====================================================================
-- V18 — Registro do direito ao esquecimento (LGPD art. 18, VI)
--
-- Por que uma coluna nova, e não só status = 'INATIVO'.
-- 'INATIVO' já existe e significa "conta desativada", que é reversível e
-- acontece por vários motivos. Exclusão a pedido do titular é outra coisa:
-- é irreversível, tem data, e é justamente ESSA data que precisa ser
-- demonstrável se alguém perguntar quando o pedido foi atendido. Derivar
-- isso de `atualizado_em` não serve — qualquer escrita posterior o move.
--
-- Por que anonimizar em vez de DELETE.
-- `lancamento`, `auditoria` e `checkin` são append-only e referenciam o
-- usuário. Apagar a linha de `usuario` quebraria a integridade do ledger,
-- e a conservação de TOKEN deixaria de fechar. O caminho correto é
-- descaracterizar o titular preservando os fatos contábeis: nome, e-mail e
-- handle viram valores aleatórios, e nada mais liga as linhas a uma pessoa.
-- Ver ADR 0011.
-- =====================================================================

ALTER TABLE usuario
    ADD COLUMN anonimizado_em TIMESTAMPTZ;

COMMENT ON COLUMN usuario.anonimizado_em IS
    'Quando o titular exerceu o direito ao esquecimento. NULL = conta normal. '
    'Preenchida junto com status=INATIVO e com a descaracterização de nome/email/handle; '
    'o ledger append-only permanece intacto.';

-- Índice parcial: conta anonimizada é a exceção, e a consulta que importa é
-- "quais foram anonimizadas", nunca "quais não foram".
CREATE INDEX idx_usuario_anonimizado
    ON usuario (anonimizado_em)
    WHERE anonimizado_em IS NOT NULL;
