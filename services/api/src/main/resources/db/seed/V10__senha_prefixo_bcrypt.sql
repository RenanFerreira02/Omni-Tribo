-- V10 — Adiciona prefixo {bcrypt} nos hashes do seed para compatibilidade com
-- DelegatingPasswordEncoder (introduzido na F2).
-- O V9 armazenou hashes bcrypt brutos ($2a$...) sem o prefixo de algoritmo
-- que o DelegatingPasswordEncoder exige ({bcrypt}$2a$...).
-- Idempotente: a cláusula WHERE garante que não duplica o prefixo em re-execuções.
UPDATE usuario
SET senha_hash = CONCAT('{bcrypt}', senha_hash)
WHERE senha_hash NOT LIKE '{%}%';
