-- =============================================================================
-- V14 — Backoff da outbox (F5)
--
-- A tabela outbox existe desde a V7 e nunca teve produtor nem consumidor. F5 é a
-- primeira fase que escreve nela (conclusão de missão) e a primeira que a drena,
-- então é agora que ela precisa das colunas que um drenador com retry exige.
--
-- `tentativas` já existia e sozinha não basta: contar falhas não diz QUANDO
-- retentar. Sem instante de próxima tentativa, um evento cujo despacho falha
-- volta no lote seguinte imediatamente e o drenador gira em falso contra a mesma
-- linha a cada varredura, queimando CPU e inflando `tentativas` em segundos.
--
-- Sem GRANT novo: outbox já tem SELECT, INSERT, UPDATE, DELETE para omnitribo_app
-- desde a V7, e privilégio é por tabela — sobrevive ao ALTER TABLE.
-- =============================================================================

-- DEFAULT NOW() e NOT NULL: uma linha nunca tentada tem de ser elegível
-- imediatamente, que é a semântica correta para "nunca tentei despachar isto".
-- Nullable exigiria `proxima_tentativa_em IS NULL OR proxima_tentativa_em <= now()`
-- em toda consulta, e a primeira que esquecesse do IS NULL deixaria de despachar
-- justamente os eventos novos.
ALTER TABLE outbox ADD COLUMN proxima_tentativa_em TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Última mensagem de erro do despacho. Diagnóstico: sem ela, um evento parado com
-- tentativas=5 não diz por quê, e o operador só descobre reproduzindo.
ALTER TABLE outbox ADD COLUMN ultimo_erro TEXT;

-- idx_outbox_nao_publicado (V8) indexa criado_em e ignora o backoff: com ele, um
-- evento em espera continuaria no topo de todo lote — exatamente o giro em falso
-- descrito acima. Substituído por um que ordena pelo que decide a elegibilidade.
DROP INDEX idx_outbox_nao_publicado;
CREATE INDEX idx_outbox_pendente
    ON outbox (proxima_tentativa_em)
    WHERE publicado_em IS NULL;
