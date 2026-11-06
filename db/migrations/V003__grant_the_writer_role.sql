-- The service reads and writes the state of the sagas and nothing else. workflow_writer has no login and no
-- password: csp-infra-postgres creates the login of the service and makes it a member of this role. It cannot delete
-- a saga and has no privilege in any domain schema.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'workflow_writer') THEN
        CREATE ROLE workflow_writer NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA workflow TO workflow_writer;
GRANT SELECT, INSERT, UPDATE ON workflow.saga TO workflow_writer;
