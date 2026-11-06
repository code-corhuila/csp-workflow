package co.edu.corhuila.csp.workflow.application.port.in;

import co.edu.corhuila.csp.workflow.domain.saga.SagaInstance;
import java.util.UUID;

public interface GetSaga {

    /** A saga of another user is as unknown as one that does not exist. */
    SagaInstance get(UUID userId, UUID sagaId);
}
