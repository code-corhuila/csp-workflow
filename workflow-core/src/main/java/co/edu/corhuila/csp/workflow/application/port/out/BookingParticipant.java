package co.edu.corhuila.csp.workflow.application.port.out;

import java.util.List;
import java.util.UUID;

/**
 * The Booking domain as a participant. Each call carries an idempotency key, so a retried step never applies
 * twice, and every undo is idempotent. A refused or unreachable call is a {@link ParticipantFailedException}.
 */
public interface BookingParticipant {

    UUID hold(String key, UUID userId, UUID showtimeId, List<String> seatLabels);

    void confirm(String key, UUID reservationId);

    void release(String key, UUID reservationId);
}
