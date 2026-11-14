-- Reverts V006. The correlation ids stored so far are lost: after it, a resumed saga uses its own id again.
ALTER TABLE workflow.saga DROP CONSTRAINT chk_saga_correlation_id_length;
ALTER TABLE workflow.saga DROP COLUMN correlation_id;
