package co.edu.corhuila.csp.workflow.adapter.out.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.csp.workflow.application.port.in.BookSeatsCommand;
import co.edu.corhuila.csp.workflow.application.port.in.RecoveryResult;
import co.edu.corhuila.csp.workflow.application.port.out.BookingParticipant;
import co.edu.corhuila.csp.workflow.application.usecase.RunBookSeatsSaga;
import co.edu.corhuila.csp.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.csp.workflow.domain.saga.SagaStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The reason for a persistent store (norm 5.8.4): a process dies in the middle of a saga and another one, started
 * later on the same database, finishes it without running any step twice. The two processes share nothing but
 * PostgreSQL.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SagaRestartTest {

    private static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SHOWTIME = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RESERVATION = UUID.fromString("9cfcfeec-c4b6-4d76-a0b4-f0db602d7caa");
    private static final UUID SAGA_ID = UUID.fromString("35920dc9-bd55-41a0-8e41-5832377383e5");

    /** The process dies: not a refusal of Booking, so the orchestrator cannot handle it and leaves the saga as it is. */
    private static final class ProcessDied extends RuntimeException { }

    private static final class RecordingBooking implements BookingParticipant {
        final List<String> calls = new ArrayList<>();
        boolean dieOnConfirm;

        @Override
        public UUID hold(String key, UUID userId, UUID showtimeId, List<String> seatLabels) {
            calls.add("hold " + key);
            return RESERVATION;
        }

        @Override
        public void confirm(String key, UUID reservationId) {
            calls.add("confirm " + key);
            if (dieOnConfirm) {
                throw new ProcessDied();
            }
        }

        @Override
        public void release(String key, UUID reservationId) {
            calls.add("release " + key);
        }
    }

    private PGSimpleDataSource dataSource;

    @BeforeEach
    void freshDatabase() throws SQLException {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(System.getenv("TEST_DATABASE_URL"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS workflow CASCADE");
        }
        Flyway.configure().dataSource(dataSource).locations("filesystem:../db/migrations")
                .schemas("workflow").defaultSchema("workflow").load().migrate();
    }

    private RunBookSeatsSaga process(RecordingBooking booking, Instant now) {
        return new RunBookSeatsSaga(booking, new JdbcSagaStore(dataSource), () -> SAGA_ID,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void aProcessThatDiedDuringTheConfirmationIsFinishedByTheNextOneWithoutHoldingAgain() {
        RecordingBooking dying = new RecordingBooking();
        dying.dieOnConfirm = true;
        BookSeatsCommand command = new BookSeatsCommand(USER, "key-0000000000001", SHOWTIME, List.of("A1", "A2"));

        assertThrows(ProcessDied.class, () -> process(dying, Instant.parse("2026-11-05T10:00:00Z")).start(command));

        SagaInstance cut = new JdbcSagaStore(dataSource).findById(SAGA_ID).orElseThrow();
        assertEquals(SagaStatus.RUNNING, cut.status());
        assertEquals("confirm-reservation", cut.currentStep());
        assertEquals(List.of("hold-seats"), cut.completedSteps());

        RecordingBooking next = new RecordingBooking();
        RecoveryResult result = process(next, Instant.parse("2026-11-05T10:05:00Z")).recover(Instant.parse("2026-11-05T10:05:00Z"));

        assertEquals(List.of(SAGA_ID), result.resumed());
        SagaInstance finished = new JdbcSagaStore(dataSource).findById(SAGA_ID).orElseThrow();
        assertEquals(SagaStatus.COMPLETED, finished.status());
        assertEquals(RESERVATION, finished.reservationId());
        assertEquals(List.of("confirm " + SAGA_ID + ":confirm-reservation"), next.calls);
        assertEquals(List.of("hold " + SAGA_ID + ":hold-seats", "confirm " + SAGA_ID + ":confirm-reservation"), dying.calls);
        assertEquals(0, process(new RecordingBooking(), Instant.parse("2026-11-05T10:06:00Z"))
                .recover(Instant.parse("2026-11-05T10:06:00Z")).resumed().size());
    }

    @Test
    void theRepeatedRequestOfAFinishedSagaStillRunsNothing() {
        RecordingBooking booking = new RecordingBooking();
        BookSeatsCommand command = new BookSeatsCommand(USER, "key-0000000000001", SHOWTIME, List.of("A1"));
        process(booking, Instant.parse("2026-11-05T10:00:00Z")).start(command);
        booking.calls.clear();

        var replay = process(booking, Instant.parse("2026-11-05T10:10:00Z")).start(command);

        assertEquals(false, replay.created());
        assertEquals(List.of(), booking.calls);
    }
}
