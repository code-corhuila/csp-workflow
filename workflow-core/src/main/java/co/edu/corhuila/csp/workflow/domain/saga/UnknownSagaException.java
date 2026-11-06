package co.edu.corhuila.csp.workflow.domain.saga;

import java.util.UUID;

/** The saga does not exist or belongs to another user: the caller cannot tell the two apart. */
public class UnknownSagaException extends RuntimeException {

    public UnknownSagaException(UUID sagaId) {
        super("Unknown saga " + sagaId);
    }
}
