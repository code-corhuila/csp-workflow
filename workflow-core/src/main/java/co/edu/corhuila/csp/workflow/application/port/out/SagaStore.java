package co.edu.corhuila.csp.workflow.application.port.out;

import co.edu.corhuila.csp.workflow.domain.saga.SagaInstance;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Where the sagas live. The orchestrator saves after every change so a restart can resume or undo (norm 5.8.4). */
public interface SagaStore {

    void save(SagaInstance saga);

    Optional<SagaInstance> findById(UUID id);

    Optional<SagaInstance> findByUserAndKey(UUID userId, String idempotencyKey);

    /** The sagas still running whose last change is older than {@code cutoff}, the oldest first. */
    List<SagaInstance> findRunningUpdatedBefore(Instant cutoff);
}
