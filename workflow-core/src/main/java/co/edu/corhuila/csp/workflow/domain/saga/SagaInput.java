package co.edu.corhuila.csp.workflow.domain.saga;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What the saga was asked to do. It is kept with the saga so a restart can repeat a step, and its fingerprint tells
 * a replay of the same request from another request that reuses the same Idempotency-Key.
 */
public record SagaInput(UUID showtimeId, List<String> seatLabels) {

    public SagaInput {
        Objects.requireNonNull(showtimeId);
        seatLabels = List.copyOf(seatLabels);
    }

    /** Equal for two requests that ask for the same seats of the same showtime, in any order. */
    public String fingerprint() {
        return showtimeId + ":" + seatLabels.stream().sorted().collect(Collectors.joining(","));
    }
}
