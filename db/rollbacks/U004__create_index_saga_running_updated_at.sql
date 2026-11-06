-- Reverts V004. The recovery at startup still works, with a scan of the sagas.
DROP INDEX workflow.idx_saga_running_updated_at;
