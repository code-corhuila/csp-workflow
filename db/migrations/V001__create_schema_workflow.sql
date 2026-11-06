-- Schema of the state of the sagas (ADR-065). No -db repository owns it: the topology gives none to the cross-cutting
-- repositories, so its migrations live here, as the ones of schema worker do in csp-worker (ADR-014). It holds the
-- state of the orchestration and no data of any domain. Run by the migration executor, never by the service itself.
CREATE SCHEMA IF NOT EXISTS workflow;
