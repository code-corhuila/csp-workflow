package co.edu.corhuila.csp.workflow.adapter.out.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.workflow.domain.saga.SagaInput;
import co.edu.corhuila.csp.workflow.domain.saga.SagaInstance;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemorySagaStoreTest {

    private static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final SagaInput INPUT = new SagaInput(UUID.fromString("22222222-2222-4222-8222-222222222222"), List.of("A1", "A2"));

    private static SagaInstance saga(UUID user, String key) {
        return SagaInstance.start(UUID.randomUUID(), "book-seats", user, "corr-1", key, INPUT, Instant.parse("2026-11-05T10:00:00Z"));
    }

    @Test
    void aSavedSagaIsFoundByIdAndByUserAndKey() {
        InMemorySagaStore store = new InMemorySagaStore();
        SagaInstance saga = saga(USER, "key-0000000000001");

        store.save(saga);

        assertEquals(saga, store.findById(saga.id()).orElseThrow());
        assertEquals(saga, store.findByUserAndKey(USER, "key-0000000000001").orElseThrow());
    }

    @Test
    void theKeyBelongsToTheUser() {
        InMemorySagaStore store = new InMemorySagaStore();
        store.save(saga(USER, "key-0000000000001"));

        assertTrue(store.findByUserAndKey(UUID.randomUUID(), "key-0000000000001").isEmpty());
        assertTrue(store.findByUserAndKey(USER, "other-key-000000").isEmpty());
        assertTrue(store.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void savingAgainReplacesTheSaga() {
        InMemorySagaStore store = new InMemorySagaStore();
        SagaInstance saga = saga(USER, "key-0000000000001");
        store.save(saga);
        saga.complete(Instant.parse("2026-11-05T10:00:05Z"));

        store.save(saga);

        assertEquals(1, store.size());
    }

    @Test
    void theRunningSagasBeforeTheCutoffAreFound() {
        InMemorySagaStore store = new InMemorySagaStore();
        SagaInstance running = saga(USER, "key-0000000000001");
        SagaInstance finished = saga(USER, "key-0000000000002");
        finished.complete(Instant.parse("2026-11-05T10:00:01Z"));
        store.save(running);
        store.save(finished);

        assertEquals(List.of(running), store.findRunningUpdatedBefore(Instant.parse("2026-11-05T11:00:00Z")));
        assertTrue(store.findRunningUpdatedBefore(Instant.parse("2026-11-05T09:00:00Z")).isEmpty());
    }
}
