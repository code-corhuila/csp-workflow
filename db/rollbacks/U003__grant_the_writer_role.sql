-- Reverts V003. The role may belong to the login of the service: drop it only when nobody is a member.
REVOKE ALL ON workflow.saga FROM workflow_writer;
REVOKE ALL ON SCHEMA workflow FROM workflow_writer;
DROP ROLE IF EXISTS workflow_writer;
