package co.edu.corhuila.csp.workflow.app;

import co.edu.corhuila.csp.workflow.application.port.in.RecoverSagas;
import co.edu.corhuila.csp.workflow.application.port.in.RecoveryResult;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Finishes the sagas the previous process left running. It runs once, when the service starts, and it is not a
 * scheduled job: a periodic sweep would be a background process and belongs to csp-worker (norm 4.3, ADR-065).
 */
class SagaRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SagaRecoveryRunner.class);

    private final RecoverSagas recoverSagas;
    private final Clock clock;

    SagaRecoveryRunner(RecoverSagas recoverSagas, Clock clock) {
        this.recoverSagas = recoverSagas;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        RecoveryResult result = recoverSagas.recover(clock.instant());
        log.info("saga recovery resumed={} skipped={} failed={}", result.resumed().size(), result.skipped().size(),
                result.failed().size());
        if (!result.failed().isEmpty()) {
            log.error("saga recovery failed for {}; a person must look at them", result.failed());
        }
    }
}
