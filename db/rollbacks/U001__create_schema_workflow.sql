-- Reverts V001. It refuses to run (no CASCADE) while any object is left in the schema, so U002 and U003 go first.
DROP SCHEMA workflow;
