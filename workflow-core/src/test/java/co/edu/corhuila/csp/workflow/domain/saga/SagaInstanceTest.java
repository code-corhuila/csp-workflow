package co.edu.corhuila.csp.workflow.domain.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SagaInstanceTest {

    private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-05T10:00:05Z");
    private static final SagaInput INPUT = new SagaInput(UUID.fromString("22222222-2222-4222-8222-222222222222"), List.of("A1", "A2"));

    private static SagaInstance running() {
        return SagaInstance.start(UUID.randomUUID(), "book-seats", UUID.randomUUID(), "key-0000000000001", INPUT, T0);
    }

    @Test
    void startsRunningWithNothingDone() {
        SagaInstance saga = running();

        assertEquals(SagaStatus.RUNNING, saga.status());
        assertEquals(List.of(), saga.completedSteps());
        assertNull(saga.failedStep());
        assertNull(saga.currentStep());
        assertEquals(T0, saga.createdAt());
    }

    @Test
    void aStepIsBegunThenCompleted() {
        SagaInstance saga = running();

        saga.beginStep("hold-seats", T1);
        assertEquals("hold-seats", saga.currentStep());
        saga.completeStep("hold-seats", T1);

        assertNull(saga.currentStep());
        assertEquals(List.of("hold-seats"), saga.completedSteps());
        assertEquals(T1, saga.updatedAt());
    }

    @Test
    void aStepCannotBeCompletedWithoutBeingBegun() {
        assertThrows(IllegalStateException.class, () -> running().completeStep("hold-seats", T1));
    }

    @Test
    void aFailureWithNothingCompletedEndsCompensated() {
        SagaInstance saga = running();
        saga.beginStep("hold-seats", T1);

        saga.stepFailed("hold-seats", "detail", T1);

        assertEquals(SagaStatus.COMPENSATED, saga.status());
        assertEquals("hold-seats", saga.failedStep());
    }

    @Test
    void aFailureAfterACompletedStepWaitsForItsCompensation() {
        SagaInstance saga = running();
        saga.beginStep("hold-seats", T1);
        saga.completeStep("hold-seats", T1);
        saga.beginStep("confirm-reservation", T1);

        saga.stepFailed("confirm-reservation", "detail", T1);

        assertEquals(SagaStatus.RUNNING, saga.status());
        assertEquals(List.of("hold-seats"), saga.pendingCompensations());

        saga.stepCompensated("hold-seats", T1);

        assertEquals(SagaStatus.COMPENSATED, saga.status());
        assertEquals(List.of(), saga.pendingCompensations());
        assertEquals(List.of("hold-seats"), saga.completedSteps());
    }

    @Test
    void compensationsAreListedInReverseOrder() {
        SagaInstance saga = running();
        for (String step : List.of("a", "b", "c")) {
            saga.beginStep(step, T1);
            saga.completeStep(step, T1);
        }
        saga.beginStep("d", T1);
        saga.stepFailed("d", "detail", T1);

        assertEquals(List.of("c", "b", "a"), saga.pendingCompensations());
    }

    @Test
    void aFailedCompensationEndsFailedAndKeepsTheFailedStep() {
        SagaInstance saga = running();
        saga.beginStep("hold-seats", T1);
        saga.completeStep("hold-seats", T1);
        saga.beginStep("confirm-reservation", T1);
        saga.stepFailed("confirm-reservation", "detail", T1);

        saga.compensationFailed("release-seats", "no answer", T1);

        assertEquals(SagaStatus.FAILED, saga.status());
        assertEquals("confirm-reservation", saga.failedStep());
    }

    @Test
    void aFinishedSagaAcceptsNoMoreSteps() {
        SagaInstance saga = running();
        saga.complete(T1);

        assertEquals(SagaStatus.COMPLETED, saga.status());
        assertThrows(IllegalStateException.class, () -> saga.beginStep("hold-seats", T1));
    }

    @Test
    void aSnapshotRestoresTheSameSagaInTheMiddleOfAStep() {
        SagaInstance saga = running();
        saga.beginStep("hold-seats", T1);
        saga.attachReservation(UUID.fromString("9cfcfeec-c4b6-4d76-a0b4-f0db602d7caa"));
        saga.completeStep("hold-seats", T1);
        saga.beginStep("confirm-reservation", T1);

        SagaInstance restored = SagaInstance.restore(saga.snapshot());

        assertEquals(saga.snapshot(), restored.snapshot());
        assertEquals("confirm-reservation", restored.currentStep());
        assertEquals(List.of("hold-seats"), restored.completedSteps());
        assertEquals(INPUT, restored.input());
    }

    @Test
    void aRestoredSagaKeepsItsCompensationsInReverseOrder() {
        SagaInstance saga = running();
        for (String step : List.of("a", "b")) {
            saga.beginStep(step, T1);
            saga.completeStep(step, T1);
        }
        saga.beginStep("c", T1);
        saga.stepFailed("c", "detail", T1);
        saga.stepCompensated("b", T1);

        SagaInstance restored = SagaInstance.restore(saga.snapshot());

        assertEquals(List.of("a"), restored.pendingCompensations());
        assertEquals("c", restored.failedStep());
    }

    @Test
    void theVersionIsSetByTheStoreThatPersistsTheSaga() {
        SagaInstance saga = running();
        assertEquals(0, saga.version());

        saga.persistedAs(3);

        assertEquals(3, saga.version());
        assertEquals(3, saga.snapshot().version());
    }
}
