package co.edu.corhuila.csp.workflow.adapter.out.state;

import co.edu.corhuila.csp.workflow.application.port.out.SagaStore;
import co.edu.corhuila.csp.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.csp.workflow.domain.saga.SagaStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the sagas in memory: enough to run the service and to test the orchestrator, but a restart loses them,
 * which is exactly what a saga store must not do. The PostgreSQL store of ADR-065 replaces it.
 */
public final class InMemorySagaStore implements SagaStore {

    private final Map<UUID, SagaInstance> sagas = new ConcurrentHashMap<>();

    @Override
    public void save(SagaInstance saga) {
        sagas.put(saga.id(), saga);
    }

    @Override
    public Optional<SagaInstance> findById(UUID id) {
        return Optional.ofNullable(sagas.get(id));
    }

    @Override
    public Optional<SagaInstance> findByUserAndKey(UUID userId, String idempotencyKey) {
        return sagas.values().stream()
                .filter(saga -> saga.userId().equals(userId) && saga.idempotencyKey().equals(idempotencyKey))
                .findFirst();
    }

    @Override
    public List<SagaInstance> findRunningUpdatedBefore(Instant cutoff) {
        return sagas.values().stream()
                .filter(saga -> saga.status() == SagaStatus.RUNNING && saga.updatedAt().isBefore(cutoff))
                .sorted(Comparator.comparing(SagaInstance::updatedAt)).toList();
    }

    int size() {
        return sagas.size();
    }
}
