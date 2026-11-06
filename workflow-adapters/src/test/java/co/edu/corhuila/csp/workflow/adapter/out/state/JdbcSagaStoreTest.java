package co.edu.corhuila.csp.workflow.adapter.out.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.workflow.application.port.out.SagaAlreadyStartedException;
import co.edu.corhuila.csp.workflow.application.port.out.StaleSagaException;
import co.edu.corhuila.csp.workflow.domain.saga.SagaInput;
import co.edu.corhuila.csp.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.csp.workflow.domain.saga.SagaStatus;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Runs against a real PostgreSQL (Annex C): TEST_DATABASE_URL points at a disposable database. The migrations of
 * `db/` are applied by Flyway, as the tooling container does, so the tests check the real schema (ADR-065).
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcSagaStoreTest {

    private static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RESERVATION = UUID.fromString("9cfcfeec-c4b6-4d76-a0b4-f0db602d7caa");
    private static final SagaInput INPUT = new SagaInput(UUID.fromString("22222222-2222-4222-8222-222222222222"),
            List.of("A1", "A2"));
    private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-05T10:00:05Z");

    private static PGSimpleDataSource dataSource;
    private JdbcSagaStore store;

    @BeforeAll
    static void migrate() {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(System.getenv("TEST_DATABASE_URL"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS workflow CASCADE");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        migration().migrate();
    }

    private static Flyway migration() {
        return Flyway.configure().dataSource(dataSource).locations("filesystem:../db/migrations")
                .schemas("workflow").defaultSchema("workflow").validateMigrationNaming(true).load();
    }

    @BeforeEach
    void clean() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE workflow.saga");
        }
        store = new JdbcSagaStore(dataSource);
    }

    private static SagaInstance saga(UUID user, String key) {
        return SagaInstance.start(UUID.randomUUID(), "book-seats", user, key, INPUT, T0);
    }

    @Test
    void aNewSagaIsInsertedAndFoundByIdInTheSameState() {
        SagaInstance saga = saga(USER, "key-0000000000001");

        store.save(saga);

        assertEquals(1, saga.version());
        assertEquals(saga.snapshot(), store.findById(saga.id()).orElseThrow().snapshot());
    }

    @Test
    void everyChangeOfTheSagaIsPersistedAndTheVersionGrows() {
        SagaInstance saga = saga(USER, "key-0000000000001");
        store.save(saga);
        saga.beginStep("hold-seats", T1);
        store.save(saga);
        saga.attachReservation(RESERVATION);
        saga.completeStep("hold-seats", T1);
        saga.beginStep("confirm-reservation", T1);
        store.save(saga);

        SagaInstance loaded = store.findById(saga.id()).orElseThrow();

        assertEquals(3, loaded.version());
        assertEquals(saga.snapshot(), loaded.snapshot());
        assertEquals("confirm-reservation", loaded.currentStep());
        assertEquals(List.of("hold-seats"), loaded.completedSteps());
        assertEquals(RESERVATION, loaded.reservationId());
    }

    @Test
    void aFailedAndCompensatedSagaKeepsItsDetailAndItsCompensations() {
        SagaInstance saga = saga(USER, "key-0000000000001");
        store.save(saga);
        saga.beginStep("hold-seats", T1);
        saga.completeStep("hold-seats", T1);
        saga.beginStep("confirm-reservation", T1);
        saga.stepFailed("confirm-reservation", "booking confirm answered 422", T1);
        saga.stepCompensated("hold-seats", T1);
        store.save(saga);

        SagaInstance loaded = store.findById(saga.id()).orElseThrow();

        assertEquals(SagaStatus.COMPENSATED, loaded.status());
        assertEquals("confirm-reservation", loaded.failedStep());
        assertEquals("booking confirm answered 422", loaded.failureDetail());
        assertEquals(List.of(), loaded.pendingCompensations());
    }

    @Test
    void aSagaIsFoundByItsUserAndKeyAndOnlyByThem() {
        SagaInstance saga = saga(USER, "key-0000000000001");
        store.save(saga);

        assertEquals(saga.id(), store.findByUserAndKey(USER, "key-0000000000001").orElseThrow().id());
        assertTrue(store.findByUserAndKey(UUID.randomUUID(), "key-0000000000001").isEmpty());
        assertTrue(store.findByUserAndKey(USER, "other-key-000000").isEmpty());
        assertTrue(store.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void theSameUserAndKeyCannotStartTwoSagasButAnotherUserCan() {
        store.save(saga(USER, "key-0000000000001"));

        assertThrows(SagaAlreadyStartedException.class, () -> store.save(saga(USER, "key-0000000000001")));
        store.save(saga(UUID.randomUUID(), "key-0000000000001"));
    }

    @Test
    void aWriterThatReadAnOldVersionCannotOverwriteTheNewerOne() {
        SagaInstance saga = saga(USER, "key-0000000000001");
        store.save(saga);
        SagaInstance first = store.findById(saga.id()).orElseThrow();
        SagaInstance second = store.findById(saga.id()).orElseThrow();
        first.beginStep("hold-seats", T1);
        store.save(first);
        second.beginStep("hold-seats", T1);

        assertThrows(StaleSagaException.class, () -> store.save(second));
        assertEquals(2, store.findById(saga.id()).orElseThrow().version());
    }

    @Test
    void onlyTheSagasStillRunningBeforeTheCutoffAreFoundOldestFirst() {
        SagaInstance older = SagaInstance.start(UUID.randomUUID(), "book-seats", USER, "key-0000000000001", INPUT, T0);
        SagaInstance newer = SagaInstance.start(UUID.randomUUID(), "book-seats", USER, "key-0000000000002", INPUT, T0.plusSeconds(10));
        SagaInstance finished = SagaInstance.start(UUID.randomUUID(), "book-seats", USER, "key-0000000000003", INPUT, T0);
        SagaInstance afterCutoff = SagaInstance.start(UUID.randomUUID(), "book-seats", USER, "key-0000000000004", INPUT, T0.plusSeconds(600));
        finished.complete(T0);
        for (SagaInstance saga : List.of(newer, older, finished, afterCutoff)) {
            store.save(saga);
        }

        List<SagaInstance> found = store.findRunningUpdatedBefore(T0.plusSeconds(60));

        assertEquals(List.of(older.id(), newer.id()), found.stream().map(SagaInstance::id).toList());
    }

    @Test
    void theDatabaseItselfRefusesAnUnknownStatusAndAShortKey() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            String values = " VALUES ('" + UUID.randomUUID() + "', 'book-seats', '%s', '" + USER + "', '%s', '{}', 1, now(), now())";
            String columns = "INSERT INTO workflow.saga (id, saga_name, status, user_id, idempotency_key, input, version, created_at, updated_at)";

            assertThrows(SQLException.class, () -> statement.execute(columns + String.format(values, "DONE", "key-0000000000001")));
            assertThrows(SQLException.class, () -> statement.execute(columns + String.format(values, "RUNNING", "short")));
        }
    }

    @Test
    void theMigrationsAreRepeatableAndCreateTheNamedConstraintsAndTheWriterRole() throws SQLException {
        assertEquals(0, migration().migrate().migrationsExecuted);

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            ResultSet constraints = statement.executeQuery("SELECT conname FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid "
                    + "JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = 'workflow' AND t.relname = 'saga'");
            List<String> names = new java.util.ArrayList<>();
            while (constraints.next()) {
                names.add(constraints.getString(1));
            }
            assertTrue(names.containsAll(List.of("pk_saga", "chk_saga_status", "chk_saga_idempotency_key_length",
                    "uq_saga_user_id_idempotency_key")), names.toString());

            ResultSet writer = statement.executeQuery("SELECT has_table_privilege('workflow_writer', 'workflow.saga', 'INSERT'), "
                    + "has_table_privilege('workflow_writer', 'workflow.saga', 'UPDATE'), "
                    + "has_table_privilege('workflow_writer', 'workflow.saga', 'DELETE')");
            writer.next();
            assertTrue(writer.getBoolean(1));
            assertTrue(writer.getBoolean(2));
            assertFalse(writer.getBoolean(3));

            ResultSet index = statement.executeQuery("SELECT indexdef FROM pg_indexes WHERE schemaname = 'workflow' AND indexname = 'idx_saga_running_updated_at'");
            assertTrue(index.next());
            assertTrue(index.getString(1).contains("WHERE (status = 'RUNNING'::text)"), index.getString(1));
        }
    }
}
