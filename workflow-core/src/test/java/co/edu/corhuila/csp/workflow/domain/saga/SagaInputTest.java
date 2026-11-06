package co.edu.corhuila.csp.workflow.domain.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SagaInputTest {

    private static final UUID SHOWTIME = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void theFingerprintIgnoresTheOrderOfTheSeats() {
        assertEquals(new SagaInput(SHOWTIME, List.of("A1", "A2")).fingerprint(),
                new SagaInput(SHOWTIME, List.of("A2", "A1")).fingerprint());
    }

    @Test
    void anotherShowtimeOrAnotherSeatChangesTheFingerprint() {
        SagaInput input = new SagaInput(SHOWTIME, List.of("A1"));

        assertNotEquals(input.fingerprint(), new SagaInput(UUID.randomUUID(), List.of("A1")).fingerprint());
        assertNotEquals(input.fingerprint(), new SagaInput(SHOWTIME, List.of("B7")).fingerprint());
    }

    @Test
    void theSeatsAreCopiedSoTheInputCannotChange() {
        List<String> seats = new ArrayList<>(List.of("A1"));
        SagaInput input = new SagaInput(SHOWTIME, seats);

        seats.add("A2");

        assertEquals(List.of("A1"), input.seatLabels());
    }
}
