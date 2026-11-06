package co.edu.corhuila.csp.workflow.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.workflow.application.port.in.BookSeatsCommand;
import co.edu.corhuila.csp.workflow.application.port.in.RecoveryResult;
import co.edu.corhuila.csp.workflow.application.port.in.StartResult;
import co.edu.corhuila.csp.workflow.application.port.out.BookingParticipant;
import co.edu.corhuila.csp.workflow.application.port.out.IdGenerator;
import co.edu.corhuila.csp.workflow.application.port.out.ParticipantFailedException;
import co.edu.corhuila.csp.workflow.application.port.out.SagaAlreadyStartedException;
import co.edu.corhuila.csp.workflow.application.port.out.SagaStore;
import co.edu.corhuila.csp.workflow.application.port.out.StaleSagaException;
import co.edu.corhuila.csp.workflow.domain.saga.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.workflow.domain.saga.SagaInput;
import co.edu.corhuila.csp.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.csp.workflow.domain.saga.SagaStatus;
import co.edu.corhuila.csp.workflow.domain.saga.UnknownSagaException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RunBookSeatsSagaTest {

    private static final UUID SAGA_ID = UUID.fromString("35920dc9-bd55-41a0-8e41-5832377383e5");
    private static final UUID RESERVATION_ID = UUID.fromString("9cfcfeec-c4b6-4d76-a0b4-f0db602d7caa");
    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SHOWTIME_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String CORRELATION_ID = "corr-of-the-request";
    private static final String CORRELATION_BEFORE_RESTART = "corr-before-restart";

    private FakeBooking booking;
    private FakeStore store;
    private RunBookSeatsSaga saga;

    @BeforeEach
    void setUp() {
        booking = new FakeBooking();
        store = new FakeStore();
        IdGenerator ids = () -> SAGA_ID;
        Clock clock = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC);
        saga = new RunBookSeatsSaga(booking, store, ids, clock);
    }

    private static BookSeatsCommand command(String key, String... seats) {
        return new BookSeatsCommand(USER_ID, key, CORRELATION_ID, SHOWTIME_ID, List.of(seats));
    }

    @Test
    void happyPathHoldsThenConfirmsAndCompletes() {
        StartResult result = saga.start(command("key-0000000000001", "A1", "A2"));

        assertTrue(result.created());
        assertEquals(SagaStatus.COMPLETED, result.saga().status());
        assertEquals(List.of("hold-seats", "confirm-reservation"), result.saga().completedSteps());
        assertEquals(RESERVATION_ID, result.saga().reservationId());
        assertNull(result.saga().failedStep());
        assertEquals(List.of("hold", "confirm"), booking.calls);
    }

    @Test
    void everyStepSendsItsKeyDerivedFromTheSaga() {
        saga.start(command("key-0000000000001", "A1"));

        assertEquals(SAGA_ID + ":hold-seats", booking.keys.get("hold"));
        assertEquals(SAGA_ID + ":confirm-reservation", booking.keys.get("confirm"));
        assertEquals(USER_ID, booking.holdUser);
    }

    @Test
    void theStateIsSavedBeforeAndAfterEveryStep() {
        saga.start(command("key-0000000000001", "A1"));

        assertEquals(List.of(
                "RUNNING|-|-",
                "RUNNING|-|hold-seats",
                "RUNNING|hold-seats|-",
                "RUNNING|hold-seats|confirm-reservation",
                "COMPLETED|hold-seats,confirm-reservation|-"), store.saves);
    }

    @Test
    void aFailedHoldEndsCompensatedWithNothingToUndo() {
        booking.failOn = "hold";

        SagaInstance result = saga.start(command("key-0000000000001", "A1")).saga();

        assertEquals(SagaStatus.COMPENSATED, result.status());
        assertEquals("hold-seats", result.failedStep());
        assertEquals(List.of(), result.completedSteps());
        assertEquals(List.of("hold"), booking.calls);
        assertEquals("booking answered 503", result.failureDetail());
    }

    @Test
    void aFailedConfirmationReleasesTheHold() {
        booking.failOn = "confirm";

        SagaInstance result = saga.start(command("key-0000000000001", "A1")).saga();

        assertEquals(SagaStatus.COMPENSATED, result.status());
        assertEquals("confirm-reservation", result.failedStep());
        assertEquals(List.of("hold-seats"), result.completedSteps());
        assertEquals(List.of("hold", "confirm", "release"), booking.calls);
        assertEquals(SAGA_ID + ":release-seats", booking.keys.get("release"));
        assertEquals(RESERVATION_ID, booking.releasedReservation);
    }

    @Test
    void aFailedCompensationLeavesTheSagaFailed() {
        booking.failOn = "confirm";
        booking.failRelease = true;

        SagaInstance result = saga.start(command("key-0000000000001", "A1")).saga();

        assertEquals(SagaStatus.FAILED, result.status());
        assertEquals("confirm-reservation", result.failedStep());
        assertTrue(result.failureDetail().contains("release-seats"));
    }

    @Test
    void theSameKeyAndPayloadReturnTheSameSagaAndRunNothing() {
        StartResult first = saga.start(command("key-0000000000001", "A1", "A2"));
        booking.calls.clear();

        StartResult second = saga.start(command("key-0000000000001", "A2", "A1"));

        assertFalse(second.created());
        assertEquals(first.saga().id(), second.saga().id());
        assertEquals(List.of(), booking.calls);
    }

    @Test
    void twoRequestsWithTheSameKeyAtTheSameTimeAnswerAsOneSagaAndTheLoserRunsNothing() {
        store.racer = SagaInstance.start(UUID.fromString("44444444-4444-4444-8444-444444444444"), "book-seats", USER_ID, CORRELATION_BEFORE_RESTART,
                "key-0000000000001", new SagaInput(SHOWTIME_ID, List.of("A1")), Instant.parse("2026-11-05T09:59:59Z"));

        StartResult result = saga.start(command("key-0000000000001", "A1"));

        assertFalse(result.created());
        assertEquals(UUID.fromString("44444444-4444-4444-8444-444444444444"), result.saga().id());
        assertEquals(List.of(), booking.calls);
    }

    @Test
    void theLoserOfTheRaceWithAnotherPayloadIsAConflict() {
        store.racer = SagaInstance.start(UUID.fromString("44444444-4444-4444-8444-444444444444"), "book-seats", USER_ID, CORRELATION_BEFORE_RESTART,
                "key-0000000000001", new SagaInput(SHOWTIME_ID, List.of("A1")), Instant.parse("2026-11-05T09:59:59Z"));

        assertThrows(IdempotencyKeyConflictException.class, () -> saga.start(command("key-0000000000001", "B7")));
        assertEquals(List.of(), booking.calls);
    }

    private static final Instant EARLY = Instant.parse("2026-11-05T09:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-11-05T10:00:00Z");

    /** A saga that was running when the process died, saved as the orchestrator leaves it before each call. */
    private SagaInstance interrupted(UUID id, String stoppedAt) {
        SagaInstance cut = SagaInstance.start(id, "book-seats", USER_ID, CORRELATION_BEFORE_RESTART, "key-" + id, new SagaInput(SHOWTIME_ID, List.of("A1")), EARLY);
        if (!stoppedAt.equals("inserted")) {
            cut.beginStep("hold-seats", EARLY);
        }
        if (!stoppedAt.equals("inserted") && !stoppedAt.equals("hold")) {
            cut.attachReservation(RESERVATION_ID);
            cut.completeStep("hold-seats", EARLY);
        }
        if (stoppedAt.equals("confirm") || stoppedAt.equals("compensation")) {
            cut.beginStep("confirm-reservation", EARLY);
        }
        if (stoppedAt.equals("compensation")) {
            cut.stepFailed("confirm-reservation", "booking confirm answered 503", EARLY);
        }
        store.save(cut);
        return cut;
    }

    @Test
    void aSagaCutBeforeItsFirstCallRunsFromTheBeginning() {
        interrupted(SAGA_ID, "inserted");

        RecoveryResult result = saga.recover(CUTOFF);

        assertEquals(List.of(SAGA_ID), result.resumed());
        assertEquals(SagaStatus.COMPLETED, store.findById(SAGA_ID).orElseThrow().status());
        assertEquals(List.of("hold", "confirm"), booking.calls);
    }

    @Test
    void aSagaCutDuringTheHoldRepeatsItWithTheSameKey() {
        interrupted(SAGA_ID, "hold");

        saga.recover(CUTOFF);

        assertEquals(SagaStatus.COMPLETED, store.findById(SAGA_ID).orElseThrow().status());
        assertEquals(List.of("hold", "confirm"), booking.calls);
        assertEquals(SAGA_ID + ":hold-seats", booking.keys.get("hold"));
    }

    @Test
    void aSagaCutAfterTheHoldOnlyConfirms() {
        interrupted(SAGA_ID, "held");

        saga.recover(CUTOFF);

        assertEquals(SagaStatus.COMPLETED, store.findById(SAGA_ID).orElseThrow().status());
        assertEquals(List.of("confirm"), booking.calls);
        assertEquals(RESERVATION_ID, store.findById(SAGA_ID).orElseThrow().reservationId());
    }

    @Test
    void aSagaCutDuringTheConfirmationRepeatsItWithTheSameKeyAndHoldsNothingAgain() {
        interrupted(SAGA_ID, "confirm");

        saga.recover(CUTOFF);

        assertEquals(SagaStatus.COMPLETED, store.findById(SAGA_ID).orElseThrow().status());
        assertEquals(List.of("confirm"), booking.calls);
        assertEquals(SAGA_ID + ":confirm-reservation", booking.keys.get("confirm"));
    }

    @Test
    void aSagaCutDuringItsCompensationFinishesUndoingIt() {
        interrupted(SAGA_ID, "compensation");

        saga.recover(CUTOFF);

        SagaInstance recovered = store.findById(SAGA_ID).orElseThrow();
        assertEquals(SagaStatus.COMPENSATED, recovered.status());
        assertEquals("confirm-reservation", recovered.failedStep());
        assertEquals(List.of("release"), booking.calls);
        assertEquals(SAGA_ID + ":release-seats", booking.keys.get("release"));
    }

    @Test
    void aResumedStepThatFailsIsCompensatedLikeAnyOther() {
        interrupted(SAGA_ID, "held");
        booking.failOn = "confirm";

        saga.recover(CUTOFF);

        assertEquals(SagaStatus.COMPENSATED, store.findById(SAGA_ID).orElseThrow().status());
        assertEquals(List.of("confirm", "release"), booking.calls);
    }

    @Test
    void onlyTheSagasThatWereRunningBeforeTheProcessStartedAreRecovered() {
        SagaInstance recent = SagaInstance.start(SAGA_ID, "book-seats", USER_ID, CORRELATION_BEFORE_RESTART, "key-recent-00000001",
                new SagaInput(SHOWTIME_ID, List.of("A1")), CUTOFF.plusSeconds(1));
        store.save(recent);
        SagaInstance done = interrupted(UUID.fromString("55555555-5555-4555-8555-555555555555"), "held");
        done.complete(EARLY);
        store.save(done);

        RecoveryResult result = saga.recover(CUTOFF);

        assertEquals(List.of(), result.resumed());
        assertEquals(List.of(), booking.calls);
    }

    @Test
    void aSagaAnotherWriterAlreadyMovedIsSkippedAndAFailingOneDoesNotStopTheOthers() {
        UUID stale = UUID.fromString("66666666-6666-4666-8666-666666666666");
        UUID broken = UUID.fromString("77777777-7777-4777-8777-777777777777");
        interrupted(stale, "held");
        interrupted(broken, "held");
        interrupted(SAGA_ID, "held");
        store.saveFailures.put(stale, new StaleSagaException(stale));
        store.saveFailures.put(broken, new IllegalStateException("database is down"));

        RecoveryResult result = saga.recover(CUTOFF);

        assertEquals(List.of(stale), result.skipped());
        assertEquals(List.of(broken), result.failed());
        assertEquals(List.of(SAGA_ID), result.resumed());
    }

    @Test
    void theSameKeyWithAnotherPayloadIsAConflict() {
        saga.start(command("key-0000000000001", "A1"));

        assertThrows(IdempotencyKeyConflictException.class, () -> saga.start(command("key-0000000000001", "B7")));
    }

    @Test
    void aSagaIsOnlyVisibleToItsOwner() {
        saga.start(command("key-0000000000001", "A1"));

        assertSame(SagaStatus.COMPLETED, saga.get(USER_ID, SAGA_ID).status());
        assertThrows(UnknownSagaException.class, () -> saga.get(UUID.randomUUID(), SAGA_ID));
        assertThrows(UnknownSagaException.class, () -> saga.get(USER_ID, UUID.randomUUID()));
    }

    @Test
    void everyCallOfAStartCarriesTheCorrelationIdOfTheRequest() {
        saga.start(command("key-0000000000001", "A1"));

        assertEquals(List.of(CORRELATION_ID, CORRELATION_ID), booking.correlations);
    }

    @Test
    void theCompensationOfAStartCarriesTheCorrelationIdOfTheRequest() {
        booking.failOn = "confirm";

        saga.start(command("key-0000000000001", "A1"));

        assertEquals(List.of("hold", "confirm", "release"), booking.calls);
        assertEquals(List.of(CORRELATION_ID, CORRELATION_ID, CORRELATION_ID), booking.correlations);
    }

    @Test
    void aSagaRecoveredBeforeItsFirstCallKeepsTheCorrelationIdOfTheRequestThatStartedIt() {
        interrupted(SAGA_ID, "inserted");

        saga.recover(CUTOFF);

        assertEquals(List.of(CORRELATION_BEFORE_RESTART, CORRELATION_BEFORE_RESTART), booking.correlations);
    }

    @Test
    void aSagaRecoveredDuringTheConfirmationKeepsTheCorrelationIdOfTheRequestThatStartedIt() {
        interrupted(SAGA_ID, "confirm");

        saga.recover(CUTOFF);

        assertEquals(List.of("confirm"), booking.calls);
        assertEquals(List.of(CORRELATION_BEFORE_RESTART), booking.correlations);
    }

    @Test
    void aSagaRecoveredDuringItsCompensationKeepsTheCorrelationIdOfTheRequestThatStartedIt() {
        interrupted(SAGA_ID, "compensation");

        saga.recover(CUTOFF);

        assertEquals(List.of("release"), booking.calls);
        assertEquals(List.of(CORRELATION_BEFORE_RESTART), booking.correlations);
    }

    private static final class FakeBooking implements BookingParticipant {
        final List<String> calls = new ArrayList<>();
        final Map<String, String> keys = new HashMap<>();
        final List<String> correlations = new ArrayList<>();
        String failOn;
        boolean failRelease;
        UUID holdUser;
        UUID releasedReservation;

        @Override
        public UUID hold(String key, String correlationId, UUID userId, UUID showtimeId, List<String> seatLabels) {
            record("hold", key, correlationId);
            holdUser = userId;
            return RESERVATION_ID;
        }

        @Override
        public void confirm(String key, String correlationId, UUID reservationId) {
            record("confirm", key, correlationId);
        }

        @Override
        public void release(String key, String correlationId, UUID reservationId) {
            releasedReservation = reservationId;
            record("release", key, correlationId);
        }

        private void record(String call, String key, String correlationId) {
            calls.add(call);
            correlations.add(correlationId);
            keys.put(call, key);
            if (call.equals(failOn) || (call.equals("release") && failRelease)) {
                throw new ParticipantFailedException("booking answered 503");
            }
        }
    }

    private static final class FakeStore implements SagaStore {
        final List<String> saves = new ArrayList<>();
        /** A saga that another request inserts between the lookup and the insert of this one. */
        SagaInstance racer;
        /** Failures to raise when a given saga is saved: another writer, or a database that is down. */
        final Map<UUID, RuntimeException> saveFailures = new HashMap<>();
        private final Map<UUID, SagaInstance> byId = new HashMap<>();

        @Override
        public void save(SagaInstance saga) {
            if (saveFailures.containsKey(saga.id())) {
                throw saveFailures.get(saga.id());
            }
            if (racer != null && !byId.containsKey(saga.id())) {
                byId.put(racer.id(), racer);
                racer = null;
                throw new SagaAlreadyStartedException();
            }
            byId.put(saga.id(), saga);
            saves.add(saga.status() + "|" + join(saga.completedSteps()) + "|"
                    + (saga.currentStep() == null ? "-" : saga.currentStep()));
        }

        @Override
        public Optional<SagaInstance> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<SagaInstance> findByUserAndKey(UUID userId, String key) {
            if (racer != null) {
                return Optional.empty();
            }
            return byId.values().stream()
                    .filter(s -> s.userId().equals(userId) && s.idempotencyKey().equals(key)).findFirst();
        }

        @Override
        public List<SagaInstance> findRunningUpdatedBefore(Instant cutoff) {
            return byId.values().stream()
                    .filter(s -> s.status() == SagaStatus.RUNNING && s.updatedAt().isBefore(cutoff)).toList();
        }

        private static String join(List<String> steps) {
            return steps.isEmpty() ? "-" : String.join(",", steps);
        }
    }
}
