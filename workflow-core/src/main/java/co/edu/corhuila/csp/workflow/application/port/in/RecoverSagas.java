package co.edu.corhuila.csp.workflow.application.port.in;

import java.time.Instant;

/** Finishes the sagas a restart cut in the middle (norm 5.8.4). It runs once, when the service starts. */
public interface RecoverSagas {

    /**
     * Resumes every saga that was running and last changed before {@code cutoff}, the instant this process started:
     * it repeats the step that was begun, which is safe because every call carries the key {@code <sagaId>:<step>},
     * or finishes the compensation that was under way. A saga another writer already moved is skipped; one that
     * fails does not stop the others.
     */
    RecoveryResult recover(Instant cutoff);
}
