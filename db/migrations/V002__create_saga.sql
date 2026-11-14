-- One row per run of a saga (ADR-065, amended 2026-11-05). The steps are arrays of the row: the completed steps and
-- the compensated ones in the order they happened, and the step begun and not finished. The row has no foreign key.
-- uq_saga_user_id_idempotency_key makes the start idempotent and decides two requests that arrive together.
-- failure_detail is internal detail and is never part of an answer (norm 5.8.3). version guards every update.
CREATE TABLE workflow.saga (
    id                UUID        NOT NULL,
    saga_name         TEXT        NOT NULL,
    status            TEXT        NOT NULL,
    user_id           UUID        NOT NULL,
    idempotency_key   TEXT        NOT NULL,
    input             JSONB       NOT NULL,
    completed_steps   TEXT[]      NOT NULL DEFAULT '{}',
    compensated_steps TEXT[]      NOT NULL DEFAULT '{}',
    current_step      TEXT        NULL,
    failed_step       TEXT        NULL,
    failure_detail    TEXT        NULL,
    reservation_id    UUID        NULL,
    version           BIGINT      NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_saga PRIMARY KEY (id),
    CONSTRAINT chk_saga_status CHECK (status IN ('RUNNING', 'COMPLETED', 'COMPENSATED', 'FAILED')),
    CONSTRAINT chk_saga_idempotency_key_length CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),
    CONSTRAINT chk_saga_version CHECK (version >= 1),
    CONSTRAINT uq_saga_user_id_idempotency_key UNIQUE (user_id, idempotency_key)
);
