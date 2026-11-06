package co.edu.corhuila.csp.workflow.domain.saga;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One run of a saga. It owns the rules of its state machine: a step is begun and then completed, a failure
 * starts the compensation of the completed steps in reverse order, and a failed compensation ends the saga
 * in {@code FAILED}.
 */
public final class SagaInstance {

    private final UUID id;
    private final String sagaName;
    private final UUID userId;
    private final String idempotencyKey;
    private final SagaInput input;
    private final Instant createdAt;
    private final List<String> completedSteps = new ArrayList<>();
    private final List<String> compensatedSteps = new ArrayList<>();
    private SagaStatus status = SagaStatus.RUNNING;
    private String currentStep;
    private String failedStep;
    private String failureDetail;
    private UUID reservationId;
    private long version;
    private Instant updatedAt;

    private SagaInstance(UUID id, String sagaName, UUID userId, String idempotencyKey, SagaInput input, Instant now) {
        this.id = Objects.requireNonNull(id);
        this.sagaName = Objects.requireNonNull(sagaName);
        this.userId = Objects.requireNonNull(userId);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.input = Objects.requireNonNull(input);
        this.createdAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    public static SagaInstance start(UUID id, String sagaName, UUID userId, String idempotencyKey, SagaInput input,
                                     Instant now) {
        return new SagaInstance(id, sagaName, userId, idempotencyKey, input, now);
    }

    /** Gives back a saga a store persisted, in the very state it was saved. */
    public static SagaInstance restore(SagaSnapshot snapshot) {
        SagaInstance saga = new SagaInstance(snapshot.id(), snapshot.sagaName(), snapshot.userId(),
                snapshot.idempotencyKey(), snapshot.input(), snapshot.createdAt());
        saga.completedSteps.addAll(snapshot.completedSteps());
        saga.compensatedSteps.addAll(snapshot.compensatedSteps());
        saga.status = snapshot.status();
        saga.currentStep = snapshot.currentStep();
        saga.failedStep = snapshot.failedStep();
        saga.failureDetail = snapshot.failureDetail();
        saga.reservationId = snapshot.reservationId();
        saga.version = snapshot.version();
        saga.updatedAt = snapshot.updatedAt();
        return saga;
    }

    public SagaSnapshot snapshot() {
        return new SagaSnapshot(id, sagaName, userId, idempotencyKey, input, status, List.copyOf(completedSteps),
                List.copyOf(compensatedSteps), currentStep, failedStep, failureDetail, reservationId, version,
                createdAt, updatedAt);
    }

    /** The store records the version it wrote, so the next write can be guarded by it. */
    public void persistedAs(long version) {
        this.version = version;
    }

    public void beginStep(String step, Instant now) {
        requireRunning();
        currentStep = step;
        updatedAt = now;
    }

    public void completeStep(String step, Instant now) {
        requireRunning();
        if (!step.equals(currentStep)) {
            throw new IllegalStateException("Step " + step + " was not begun");
        }
        completedSteps.add(step);
        currentStep = null;
        updatedAt = now;
    }

    public void attachReservation(UUID reservationId) {
        this.reservationId = Objects.requireNonNull(reservationId);
    }

    public void complete(Instant now) {
        requireRunning();
        status = SagaStatus.COMPLETED;
        updatedAt = now;
    }

    /** Records the failure; with nothing to undo the saga is already compensated. */
    public void stepFailed(String step, String detail, Instant now) {
        requireRunning();
        failedStep = step;
        failureDetail = detail;
        currentStep = null;
        updatedAt = now;
        endCompensatedWhenNothingIsPending();
    }

    /** The completed steps still to undo, the last completed first. */
    public List<String> pendingCompensations() {
        List<String> pending = new ArrayList<>(completedSteps);
        pending.removeAll(compensatedSteps);
        Collections.reverse(pending);
        return pending;
    }

    public void stepCompensated(String step, Instant now) {
        requireRunning();
        compensatedSteps.add(step);
        updatedAt = now;
        endCompensatedWhenNothingIsPending();
    }

    public void compensationFailed(String compensation, String detail, Instant now) {
        requireRunning();
        status = SagaStatus.FAILED;
        failureDetail = failureDetail + "; compensation " + compensation + " failed: " + detail;
        updatedAt = now;
    }

    private void endCompensatedWhenNothingIsPending() {
        if (failedStep != null && pendingCompensations().isEmpty()) {
            status = SagaStatus.COMPENSATED;
        }
    }

    private void requireRunning() {
        if (status != SagaStatus.RUNNING) {
            throw new IllegalStateException("The saga is " + status);
        }
    }

    public UUID id() { return id; }
    public String sagaName() { return sagaName; }
    public UUID userId() { return userId; }
    public String idempotencyKey() { return idempotencyKey; }
    public SagaInput input() { return input; }
    public SagaStatus status() { return status; }
    public List<String> completedSteps() { return List.copyOf(completedSteps); }
    public String currentStep() { return currentStep; }
    public String failedStep() { return failedStep; }
    public String failureDetail() { return failureDetail; }
    public UUID reservationId() { return reservationId; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
