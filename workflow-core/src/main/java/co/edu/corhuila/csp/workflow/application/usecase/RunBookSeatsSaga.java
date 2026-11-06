package co.edu.corhuila.csp.workflow.application.usecase;

import co.edu.corhuila.csp.workflow.application.port.in.BookSeatsCommand;
import co.edu.corhuila.csp.workflow.application.port.in.GetSaga;
import co.edu.corhuila.csp.workflow.application.port.in.RecoverSagas;
import co.edu.corhuila.csp.workflow.application.port.in.RecoveryResult;
import co.edu.corhuila.csp.workflow.application.port.in.StartBookSeatsSaga;
import co.edu.corhuila.csp.workflow.application.port.in.StartResult;
import co.edu.corhuila.csp.workflow.application.port.out.BookingParticipant;
import co.edu.corhuila.csp.workflow.application.port.out.IdGenerator;
import co.edu.corhuila.csp.workflow.application.port.out.ParticipantFailedException;
import co.edu.corhuila.csp.workflow.application.port.out.SagaAlreadyStartedException;
import co.edu.corhuila.csp.workflow.application.port.out.SagaStore;
import co.edu.corhuila.csp.workflow.application.port.out.StaleSagaException;
import co.edu.corhuila.csp.workflow.domain.saga.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.csp.workflow.domain.saga.UnknownSagaException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrated Saga (ADR-066): hold the seats, then confirm the reservation. The confirmation has no
 * compensation and goes last; if it fails the hold is released. The state is saved before and after every step
 * and every call carries the key {@code <sagaId>:<step>} (Idempotent Receiver on the participant).
 */
public final class RunBookSeatsSaga implements StartBookSeatsSaga, GetSaga, RecoverSagas {

    static final String SAGA_NAME = "book-seats";
    static final String HOLD_SEATS = "hold-seats";
    static final String CONFIRM_RESERVATION = "confirm-reservation";
    static final String RELEASE_SEATS = "release-seats";

    private final BookingParticipant booking;
    private final SagaStore store;
    private final IdGenerator ids;
    private final Clock clock;

    public RunBookSeatsSaga(BookingParticipant booking, SagaStore store, IdGenerator ids, Clock clock) {
        this.booking = booking;
        this.store = store;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public StartResult start(BookSeatsCommand command) {
        Optional<SagaInstance> existing = store.findByUserAndKey(command.userId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), command);
        }
        SagaInstance saga = SagaInstance.start(ids.next(), SAGA_NAME, command.userId(), command.idempotencyKey(),
                command.input(), clock.instant());
        try {
            store.save(saga);
        } catch (SagaAlreadyStartedException race) {
            return replay(store.findByUserAndKey(command.userId(), command.idempotencyKey()).orElseThrow(), command);
        }
        run(saga);
        return new StartResult(saga, true);
    }

    @Override
    public SagaInstance get(UUID userId, UUID sagaId) {
        return store.findById(sagaId).filter(saga -> saga.userId().equals(userId))
                .orElseThrow(() -> new UnknownSagaException(sagaId));
    }

    private StartResult replay(SagaInstance saga, BookSeatsCommand command) {
        if (!saga.input().fingerprint().equals(command.input().fingerprint())) {
            throw new IdempotencyKeyConflictException();
        }
        return new StartResult(saga, false);
    }

    @Override
    public RecoveryResult recover(Instant cutoff) {
        List<UUID> resumed = new ArrayList<>();
        List<UUID> skipped = new ArrayList<>();
        List<UUID> failed = new ArrayList<>();
        for (SagaInstance saga : store.findRunningUpdatedBefore(cutoff)) {
            try {
                resume(saga);
                resumed.add(saga.id());
            } catch (StaleSagaException anotherWriterMovedIt) {
                skipped.add(saga.id());
            } catch (RuntimeException unexpected) {
                failed.add(saga.id());
            }
        }
        return new RecoveryResult(resumed, skipped, failed);
    }

    /** A saga that had already failed was compensating; any other one goes on from the step it had reached. */
    private void resume(SagaInstance saga) {
        if (saga.failedStep() != null) {
            compensate(saga);
        } else {
            run(saga);
        }
    }

    /** Runs the steps that are not completed yet, so a saga that was cut goes on from where it was. */
    private void run(SagaInstance saga) {
        try {
            if (!saga.completedSteps().contains(HOLD_SEATS)) {
                beginStep(saga, HOLD_SEATS);
                saga.attachReservation(booking.hold(key(saga, HOLD_SEATS), saga.userId(),
                        saga.input().showtimeId(), saga.input().seatLabels()));
                completeStep(saga, HOLD_SEATS);
            }
            if (!saga.completedSteps().contains(CONFIRM_RESERVATION)) {
                beginStep(saga, CONFIRM_RESERVATION);
                booking.confirm(key(saga, CONFIRM_RESERVATION), saga.reservationId());
                saga.completeStep(CONFIRM_RESERVATION, clock.instant());
            }
            saga.complete(clock.instant());
            store.save(saga);
        } catch (ParticipantFailedException failure) {
            saga.stepFailed(saga.currentStep(), failure.getMessage(), clock.instant());
            store.save(saga);
            compensate(saga);
        }
    }

    /** Undoes the completed steps, the last first. Only {@code hold-seats} has an undo: {@code release-seats}. */
    private void compensate(SagaInstance saga) {
        for (String step : saga.pendingCompensations()) {
            try {
                booking.release(key(saga, RELEASE_SEATS), saga.reservationId());
                saga.stepCompensated(step, clock.instant());
            } catch (ParticipantFailedException failure) {
                saga.compensationFailed(RELEASE_SEATS, failure.getMessage(), clock.instant());
                store.save(saga);
                return;
            }
            store.save(saga);
        }
    }

    private void beginStep(SagaInstance saga, String step) {
        saga.beginStep(step, clock.instant());
        store.save(saga);
    }

    private void completeStep(SagaInstance saga, String step) {
        saga.completeStep(step, clock.instant());
        store.save(saga);
    }

    private static String key(SagaInstance saga, String step) {
        return saga.id() + ":" + step;
    }
}
