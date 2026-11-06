package co.edu.corhuila.csp.workflow.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.corhuila.csp.workflow.application.port.in.RecoveryResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SagaRecoveryRunnerTest {

    private static final Instant STARTED_AT = Instant.parse("2026-11-05T10:00:00Z");

    @Test
    void whenTheServiceStartsItRecoversTheSagasThatWereRunningBeforeThatInstant() throws Exception {
        List<Instant> cutoffs = new ArrayList<>();
        SagaRecoveryRunner runner = new SagaRecoveryRunner(cutoff -> {
            cutoffs.add(cutoff);
            return new RecoveryResult(List.of(UUID.randomUUID()), List.of(), List.of());
        }, Clock.fixed(STARTED_AT, ZoneOffset.UTC));

        runner.run(null);

        assertEquals(List.of(STARTED_AT), cutoffs);
    }

    @Test
    void aRecoveryThatFindsNothingIsNotAnError() throws Exception {
        SagaRecoveryRunner runner = new SagaRecoveryRunner(cutoff -> new RecoveryResult(List.of(), List.of(), List.of()),
                Clock.fixed(STARTED_AT, ZoneOffset.UTC));

        runner.run(null);
    }
}
