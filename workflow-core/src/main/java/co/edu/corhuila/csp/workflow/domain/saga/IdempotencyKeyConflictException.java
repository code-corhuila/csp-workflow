package co.edu.corhuila.csp.workflow.domain.saga;

/** The Idempotency-Key was already used to start a saga with a different request. */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException() {
        super("The Idempotency-Key was already used with a different payload");
    }
}
