package co.edu.corhuila.csp.workflow.application.port.out;

import java.util.List;
import java.util.UUID;

/**
 * The Booking domain as a participant. Each call carries an idempotency key, so a retried step never applies
 * twice, and every undo is idempotent, and the correlation id of the saga, so the calls of one request can be followed. A refused or unreachable call is a {@link ParticipantFailedException}.
 */
public interface BookingParticipant {

    UUID hold(String key, String correlationId, UUID userId, UUID showtimeId, List<String> seatLabels);

    void confirm(String key, String correlationId, UUID reservationId);

    void release(String key, String correlationId, UUID reservationId);
}
