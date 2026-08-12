-- Alarga auditoria.correlation_id de VARCHAR(36) para VARCHAR(64).
--
-- 36 era o tamanho de um UUID com hífens, e a suposição implícita era que todo correlation-id seria
-- um UUID nosso. Não é: o cliente pode mandar o dele pelo header X-Correlation-Id, e o formato mais
-- comum em tracing distribuído é o `traceparent` do W3C, que tem 55 caracteres. Truncar em 36 corta
-- o trace-id ao meio e destrói exatamente a correlação que é a única razão desta coluna existir.
--
-- Havia um efeito pior que a perda de correlação, e ele era acionável SEM AUTENTICAÇÃO: nada
-- truncava o valor antes do INSERT, então um header mais longo estourava com SQLState 22001
-- (string_data_right_truncation) durante o flush. Como auditoriaService.gravar roda DENTRO da
-- transação de login, o login inteiro respondia 500 — para qualquer um, com uma requisição, e sem
-- precisar de má-fé, já que um traceparent legítimo bastava.
--
-- A correção completa tem três camadas, e esta é a terceira:
--   1. CorrelationIdFilter só aceita [A-Za-z0-9._-]{1,64}; fora disso, gera um id novo.
--      (fecha também a forja de linha de log por CR/LF no header)
--   2. o construtor de Auditoria trunca ip, user_agent, correlation_id e entidade_id — é o único
--      choke point que cobre os três gravadores da tabela;
--   3. esta coluna, que passa a comportar o formato que de fato circula.
-- ddl-auto: validate confere que a coluna existe com o tipo declarado; ele NÃO impõe limite em
-- runtime, então nenhuma das três camadas é dispensável.
--
-- Sem GRANT novo: privilégio no PostgreSQL é por TABELA e sobrevive ao ALTER COLUMN. O
-- REVOKE UPDATE, DELETE que a V2 aplicou a `auditoria` para omnitribo_app continua valendo, e
-- MigracaoTest trava essa matriz.

ALTER TABLE auditoria ALTER COLUMN correlation_id TYPE VARCHAR(64);

COMMENT ON COLUMN auditoria.correlation_id IS
  'Id de correlação da requisição. Aceita UUID e traceparent do W3C (55 chars). Vem do header '
  'X-Correlation-Id quando ele casa com [A-Za-z0-9._-]{1,64}; caso contrário é gerado pelo servidor.';
