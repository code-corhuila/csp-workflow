package co.edu.corhuila.csp.workflow.application.port.out;

/** The user already started a saga with this Idempotency-Key: another request inserted it first. */
public class SagaAlreadyStartedException extends RuntimeException {

    public SagaAlreadyStartedException() {
        super("A saga with this user and Idempotency-Key already exists");
    }
}
