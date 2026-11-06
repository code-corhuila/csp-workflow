package co.edu.corhuila.csp.workflow.domain.saga;

/** Where a saga stands. {@code FAILED} means a compensation failed and a person must decide. */
public enum SagaStatus {
    RUNNING,
    COMPLETED,
    COMPENSATED,
    FAILED
}
