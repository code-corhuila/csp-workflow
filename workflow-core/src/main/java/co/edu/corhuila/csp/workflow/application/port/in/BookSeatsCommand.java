package co.edu.corhuila.csp.workflow.application.port.in;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** The request to start {@code book-seats}. The user is the one of the validated token, never a body field. */
public record BookSeatsCommand(UUID userId, String idempotencyKey, UUID showtimeId, List<String> seatLabels) {

    public BookSeatsCommand {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(idempotencyKey);
        Objects.requireNonNull(showtimeId);
        seatLabels = List.copyOf(seatLabels);
    }

    /** Equal for two requests that ask for the same seats of the same showtime, in any order. */
    public String fingerprint() {
        return showtimeId + ":" + seatLabels.stream().sorted().collect(Collectors.joining(","));
    }
}
