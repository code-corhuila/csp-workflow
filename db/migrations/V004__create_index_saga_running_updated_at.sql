-- The recovery at startup asks for the sagas still running whose last change is older than the start of the process
-- (JdbcSagaStore.findRunningUpdatedBefore): only the running rows are indexed, which are few. The table was created by
-- V002 in the same release and holds no data yet, so the index is not built CONCURRENTLY.
CREATE INDEX idx_saga_running_updated_at ON workflow.saga (updated_at) WHERE status = 'RUNNING';
