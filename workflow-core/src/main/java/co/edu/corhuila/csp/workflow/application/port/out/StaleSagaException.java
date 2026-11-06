package co.edu.corhuila.csp.workflow.application.port.out;

import java.util.UUID;

/** The saga was changed by another writer since it was read: this write is refused instead of overwriting it. */
public class StaleSagaException extends RuntimeException {

    public StaleSagaException(UUID sagaId) {
        super("Saga " + sagaId + " was changed by another writer");
    }
}
