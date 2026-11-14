-- The login of the service (workflow_app) is created by csp-infra-postgres when the instance starts, before any migration
-- runs, and only a member of workflow_writer can read and write the sagas. As the migration executor created the role in
-- V003, it may grant it. Where the login does not exist yet (a bare database, the tests of the store) nothing is
-- granted: the migration is repeatable on the environment where the login appears later.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'workflow_app') THEN
        GRANT workflow_writer TO workflow_app;
    END IF;
END
$$;
