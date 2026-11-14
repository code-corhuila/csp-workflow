package co.edu.corhuila.csp.workflow.application.port.in;

import co.edu.corhuila.csp.workflow.domain.saga.SagaInput;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The request to start {@code book-seats}. The user is the one of the validated token, never a body field. The
 * correlation id is the one of the request, kept with the saga so its calls carry it even after a restart.
 */
public record BookSeatsCommand(UUID userId, String idempotencyKey, String correlationId, UUID showtimeId,
                               List<String> seatLabels) {

    public BookSeatsCommand {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(idempotencyKey);
        Objects.requireNonNull(correlationId);
        Objects.requireNonNull(showtimeId);
        seatLabels = List.copyOf(seatLabels);
    }

    public SagaInput input() {
        return new SagaInput(showtimeId, seatLabels);
    }
}
