package co.edu.corhuila.csp.workflow.adapter.out.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.workflow.application.port.out.ParticipantFailedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** The contract of Booking (`booking-service.yaml` 3.4.0, ADR-035) is checked against a stand-in HTTP server. */
class HttpBookingParticipantTest {

    private static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SHOWTIME = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RESERVATION = UUID.fromString("9cfcfeec-c4b6-4d76-a0b4-f0db602d7caa");
    private static final String KEY = "35920dc9-bd55-41a0-8e41-5832377383e5:hold-seats";
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Received(String method, String path, String authorization, String idempotencyKey,
                            String correlationId, String body) { }

    private record Answer(int status, String body) { }

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Queue<Answer> answers = new LinkedList<>();
    private final List<Long> sleeps = new ArrayList<>();
    private String token = "service-token-1";
    private long handlerDelayMillis;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    exchange.getRequestHeaders().getFirst("X-Correlation-Id"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (handlerDelayMillis > 0) {
                try {
                    Thread.sleep(handlerDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Answer answer = answers.isEmpty() ? new Answer(200, "{}") : answers.poll();
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        MDC.clear();
    }

    private HttpBookingParticipant participant() {
        return participant(3, Duration.ofSeconds(2));
    }

    private HttpBookingParticipant participant(int attempts, Duration timeout) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/booking";
        return new HttpBookingParticipant(base, () -> token, timeout, attempts, Duration.ofMillis(100), sleeps::add);
    }

    private static String reservationJson() {
        return "{\"id\":\"" + RESERVATION + "\",\"status\":\"HELD\"}";
    }

    @Test
    void holdPostsTheBodyOfTheContractWithTheServiceCredential() throws Exception {
        MDC.put("correlationId", "corr-1");
        answers.add(new Answer(201, reservationJson()));

        UUID id = participant().hold(KEY, USER, SHOWTIME, List.of("A1", "A2"));

        assertEquals(RESERVATION, id);
        Received call = received.get(0);
        assertEquals("POST", call.method());
        assertEquals("/api/v1/booking/internal/workflow/holds", call.path());
        assertEquals("Bearer service-token-1", call.authorization());
        assertEquals(KEY, call.idempotencyKey());
        assertEquals("corr-1", call.correlationId());
        JsonNode body = JSON.readTree(call.body());
        assertEquals(USER.toString(), body.get("userId").asText());
        assertEquals(SHOWTIME.toString(), body.get("showtimeId").asText());
        assertEquals("A1", body.get("seatLabels").get(0).asText());
        assertEquals(2, body.get("seatLabels").size());
    }

    @Test
    void aReplayedHoldAnswers200WithTheOriginalReservation() {
        answers.add(new Answer(200, reservationJson()));

        assertEquals(RESERVATION, participant().hold(KEY, USER, SHOWTIME, List.of("A1")));
    }

    @Test
    void confirmAndReleaseCallTheirOperationsWithTheirKeys() {
        participant().confirm("saga:confirm-reservation", RESERVATION);
        participant().release("saga:release-seats", RESERVATION);

        assertEquals("/api/v1/booking/internal/workflow/reservations/" + RESERVATION + "/confirm", received.get(0).path());
        assertEquals("saga:confirm-reservation", received.get(0).idempotencyKey());
        assertEquals("/api/v1/booking/internal/workflow/reservations/" + RESERVATION + "/release", received.get(1).path());
        assertEquals("saga:release-seats", received.get(1).idempotencyKey());
    }

    @Test
    void everyCallCarriesACorrelationIdEvenWithoutOneInTheContext() {
        participant().confirm("saga:confirm-reservation", RESERVATION);

        assertNotNull(received.get(0).correlationId());
        assertFalse(received.get(0).correlationId().isBlank());
    }

    @Test
    void aRefusalOfTheCallerIsNeverRetriedAndKeepsOnlyTheStatusAndCode() {
        answers.add(new Answer(422, "{\"error\":\"INVALID_STATUS_TRANSITION\",\"message\":\"expired\"}"));

        ParticipantFailedException failure = assertThrows(ParticipantFailedException.class,
                () -> participant().confirm("saga:confirm-reservation", RESERVATION));

        assertEquals(1, received.size());
        assertTrue(failure.getMessage().contains("422"));
        assertTrue(failure.getMessage().contains("INVALID_STATUS_TRANSITION"));
        assertFalse(failure.getMessage().contains("expired"));
    }

    @Test
    void a503IsRetriedWithTheSameKeyUntilItSucceeds() {
        answers.add(new Answer(503, "{}"));
        answers.add(new Answer(429, "{}"));
        answers.add(new Answer(200, "{}"));

        participant().release("saga:release-seats", RESERVATION);

        assertEquals(3, received.size());
        assertEquals(List.of("saga:release-seats", "saga:release-seats", "saga:release-seats"),
                received.stream().map(Received::idempotencyKey).toList());
        assertEquals(2, sleeps.size());
    }

    @Test
    void theWaitBetweenAttemptsIsJitteredWithinTheExponentialCeiling() {
        answers.add(new Answer(503, "{}"));
        answers.add(new Answer(503, "{}"));
        answers.add(new Answer(200, "{}"));

        participant().release("saga:release-seats", RESERVATION);

        assertTrue(sleeps.get(0) >= 0 && sleeps.get(0) <= 200);
        assertTrue(sleeps.get(1) >= 0 && sleeps.get(1) <= 400);
    }

    @Test
    void aServerThatKeepsFailingEndsAfterTheLastAttempt() {
        answers.add(new Answer(503, "{}"));
        answers.add(new Answer(503, "{}"));
        answers.add(new Answer(503, "{}"));

        ParticipantFailedException failure = assertThrows(ParticipantFailedException.class,
                () -> participant().confirm("saga:confirm-reservation", RESERVATION));

        assertEquals(3, received.size());
        assertTrue(failure.getMessage().contains("503"));
    }

    @Test
    void anUnreachableServerIsRetriedThenFailsWithoutNamingTheHost() {
        HttpBookingParticipant unreachable = new HttpBookingParticipant("http://127.0.0.1:1/api/v1/booking",
                () -> token, Duration.ofSeconds(1), 3, Duration.ofMillis(100), sleeps::add);

        ParticipantFailedException failure = assertThrows(ParticipantFailedException.class,
                () -> unreachable.confirm("saga:confirm-reservation", RESERVATION));

        assertEquals(2, sleeps.size());
        assertFalse(failure.getMessage().contains("127.0.0.1"));
    }

    @Test
    void aSlowServerTimesOutAndFails() {
        handlerDelayMillis = 800;

        assertThrows(ParticipantFailedException.class,
                () -> participant(2, Duration.ofMillis(200)).confirm("saga:confirm-reservation", RESERVATION));

        assertEquals(1, sleeps.size());
    }

    @Test
    void theTokenIsReadAgainOnEveryCallSoARotationNeedsNoRestart() {
        HttpBookingParticipant participant = participant();
        participant.confirm("saga:confirm-reservation", RESERVATION);
        token = "service-token-2";
        participant.confirm("saga:confirm-reservation", RESERVATION);

        assertEquals("Bearer service-token-1", received.get(0).authorization());
        assertEquals("Bearer service-token-2", received.get(1).authorization());
    }

    @Test
    void theTokenNeverAppearsInAFailure() {
        answers.add(new Answer(401, "{\"error\":\"UNAUTHORIZED\"}"));

        ParticipantFailedException failure = assertThrows(ParticipantFailedException.class,
                () -> participant().confirm("saga:confirm-reservation", RESERVATION));

        assertFalse(failure.getMessage().contains("service-token"));
    }
}
