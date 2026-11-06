-- Reverts V005: the login of the service stops being a member of the writer role, so it can no longer touch the sagas.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'workflow_app') THEN
        REVOKE workflow_writer FROM workflow_app;
    END IF;
END
$$;
