-- The correlation id of the request that started the saga (ADR-065), kept so that the calls a restart repeats carry it.
-- Nullable: the sagas stored before this migration have none, and the service uses the id of the saga for them. The
-- check only bounds the length to what the service accepts in X-Correlation-Id (128 characters), and a null passes it.
-- The writer role already has the privileges of the table, which cover the new column.
ALTER TABLE workflow.saga ADD COLUMN correlation_id TEXT NULL;
ALTER TABLE workflow.saga ADD CONSTRAINT chk_saga_correlation_id_length CHECK (char_length(correlation_id) BETWEEN 1 AND 128);
