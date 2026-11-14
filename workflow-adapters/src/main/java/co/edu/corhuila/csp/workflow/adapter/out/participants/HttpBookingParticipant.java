package co.edu.corhuila.csp.workflow.adapter.out.participants;

import co.edu.corhuila.csp.workflow.application.port.out.BookingParticipant;
import co.edu.corhuila.csp.workflow.application.port.out.ParticipantFailedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Adapter of the Booking domain: the internal operations of ADR-035 under {@code /internal/workflow/}. Every call
 * carries the credential of the workflow (never the token of the person), the idempotency key of the step and the
 * correlation id of the saga, which is the one of the request that started it. A refusal or an unreachable service becomes a {@link ParticipantFailedException}
 * whose message keeps the status and the error code, never the body or the host.
 */
public final class HttpBookingParticipant implements BookingParticipant {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final Supplier<String> token;
    private final Duration timeout;
    private final RetryingHttp http;

    public HttpBookingParticipant(String baseUrl, Supplier<String> token, Duration timeout, int attempts,
                                  Duration backoffBase, Sleeper sleeper) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.timeout = timeout;
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.http = new RetryingHttp(client, attempts, backoffBase, sleeper);
    }

    @Override
    public UUID hold(String key, String correlationId, UUID userId, UUID showtimeId, List<String> seatLabels) {
        String body = write(Map.of("userId", userId, "showtimeId", showtimeId, "seatLabels", seatLabels));
        String reply = call("hold", post("/internal/workflow/holds", key, correlationId,
                HttpRequest.BodyPublishers.ofString(body)));
        try {
            return UUID.fromString(JSON.readTree(reply).get("id").asText());
        } catch (IOException | RuntimeException e) {
            throw new ParticipantFailedException("booking hold answered a reservation that cannot be read");
        }
    }

    @Override
    public void confirm(String key, String correlationId, UUID reservationId) {
        call("confirm", post("/internal/workflow/reservations/" + reservationId + "/confirm", key, correlationId,
                HttpRequest.BodyPublishers.noBody()));
    }

    @Override
    public void release(String key, String correlationId, UUID reservationId) {
        call("release", post("/internal/workflow/reservations/" + reservationId + "/release", key, correlationId,
                HttpRequest.BodyPublishers.noBody()));
    }

    private HttpRequest post(String path, String key, String correlationId, HttpRequest.BodyPublisher body) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token.get())
                .header("Idempotency-Key", key)
                .header("X-Correlation-Id", correlationId)
                .header("Content-Type", "application/json")
                .POST(body)
                .build();
    }

    private String call(String operation, HttpRequest request) {
        RetryingHttp.Reply reply;
        try {
            reply = http.send(request);
        } catch (IOException e) {
            throw new ParticipantFailedException(
                    "booking " + operation + " unreachable (" + e.getClass().getSimpleName() + ")");
        }
        if (reply.status() < 200 || reply.status() >= 300) {
            throw new ParticipantFailedException(
                    "booking " + operation + " answered " + reply.status() + errorCodeOf(reply.body()));
        }
        return reply.body();
    }

    private static String errorCodeOf(String body) {
        try {
            JsonNode code = JSON.readTree(body).get("error");
            return code != null && code.isTextual() ? " " + code.asText() : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
