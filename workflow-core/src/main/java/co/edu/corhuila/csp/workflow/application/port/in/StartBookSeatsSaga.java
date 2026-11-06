package co.edu.corhuila.csp.workflow.application.port.in;

/** Starts the saga that holds seats and confirms the reservation (ADR-066). */
public interface StartBookSeatsSaga {

    /**
     * Runs the steps and returns the saga in the state it reached. The same user, key and request return the
     * first saga and run nothing; the same key with another request is an {@code IdempotencyKeyConflictException}.
     */
    StartResult start(BookSeatsCommand command);
}
