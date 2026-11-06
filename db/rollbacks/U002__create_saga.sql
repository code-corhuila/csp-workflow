-- Reverts V002. It destroys the state of every saga: run it only with the service stopped and the sagas settled.
DROP TABLE workflow.saga;
