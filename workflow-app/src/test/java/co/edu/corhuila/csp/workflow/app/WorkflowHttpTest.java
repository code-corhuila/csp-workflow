package co.edu.corhuila.csp.workflow.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole service in one process: the real filters, controller, orchestrator and Booking client, against a
 * stand-in of Booking. It checks the verification list of Annex E that does not need the real `-api`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkflowHttpTest {

    private static final String USER = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER_USER = "33333333-3333-4333-8333-333333333333";
    private static final String RESERVATION = "9cfcfeec-c4b6-4d76-a0b4-f0db602d7caa";
    private static final String SERVICE_TOKEN = "workflow-service-token";
    private static final String BODY = "{\"showtimeId\":\"22222222-2222-4222-8222-222222222222\",\"seatLabels\":[\"A1\",\"A2\"]}";
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Received(String path, String authorization, String idempotencyKey, String correlationId, String body) { }

    private static final KeyPair KEYS = newKeys();
    private static final HttpServer BOOKING = startBooking();
    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static volatile int confirmStatus = 200;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_PUBLIC_KEY", () -> "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(KEYS.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----");
        registry.add("SERVICE_TOKEN", () -> SERVICE_TOKEN);
        registry.add("BOOKING_API_URL", () -> "http://127.0.0.1:" + BOOKING.getAddress().getPort() + "/api/v1/booking");
        registry.add("HTTP_ATTEMPTS", () -> "2");
        registry.add("HTTP_BACKOFF_MS", () -> "1");
    }

    @Autowired
    private TestRestTemplate http;

    /** A new key per test: the context, and its store, are shared by all of them. */
    private String key;

    @BeforeEach
    void reset() {
        key = "key-" + UUID.randomUUID();
        RECEIVED.clear();
        confirmStatus = 200;
    }

    private static HttpHeaders headers(String token, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (key != null) {
            headers.add("Idempotency-Key", key);
        }
        return headers;
    }

    private ResponseEntity<String> start(String token, String key, String body) {
        return http.exchange("/sagas/book-seats", HttpMethod.POST, new HttpEntity<>(body, headers(token, key)), String.class);
    }

    private static JsonNode tree(ResponseEntity<String> response) throws Exception {
        return JSON.readTree(response.getBody());
    }

    @Test
    void healthAnswersWithoutToken() {
        ResponseEntity<String> response = http.getForEntity("/health", String.class);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("\"status\":\"UP\""));
    }

    @Test
    void happyPathCompletesAndEveryStepCarriesTheServiceCredentialItsKeyAndTheCorrelationId() throws Exception {
        HttpHeaders headers = headers(user(USER), key);
        headers.add("X-Correlation-Id", "corr-e2e-1");

        ResponseEntity<String> response = http.exchange("/sagas/book-seats", HttpMethod.POST,
                new HttpEntity<>(BODY, headers), String.class);

        assertEquals(201, response.getStatusCode().value());
        JsonNode saga = tree(response);
        String id = saga.get("id").asText();
        assertEquals("COMPLETED", saga.get("status").asText());
        assertEquals(RESERVATION, saga.get("reservationId").asText());
        assertTrue(response.getHeaders().getFirst("Location").endsWith("/api/v1/sagas/" + id));
        assertEquals("corr-e2e-1", response.getHeaders().getFirst("X-Correlation-Id"));

        assertEquals(2, RECEIVED.size());
        assertTrue(RECEIVED.get(0).path().endsWith("/internal/workflow/holds"));
        assertTrue(RECEIVED.get(1).path().endsWith("/internal/workflow/reservations/" + RESERVATION + "/confirm"));
        assertEquals(id + ":hold-seats", RECEIVED.get(0).idempotencyKey());
        assertEquals(id + ":confirm-reservation", RECEIVED.get(1).idempotencyKey());
        for (Received call : RECEIVED) {
            assertEquals("Bearer " + SERVICE_TOKEN, call.authorization());
            assertEquals("corr-e2e-1", call.correlationId());
        }
        assertEquals(USER, JSON.readTree(RECEIVED.get(0).body()).get("userId").asText());
    }

    @Test
    void withoutTokenIsA401AndAServiceCredentialIsA403() {
        assertEquals(401, start(null, key, BODY).getStatusCode().value());
        assertEquals(401, start("garbage", key, BODY).getStatusCode().value());
        assertEquals(403, start(service(), key, BODY).getStatusCode().value());
        assertEquals(0, RECEIVED.size());
    }

    @Test
    void aBadRequestIsA400WithOneDetailPerFieldAndRunsNothing() throws Exception {
        ResponseEntity<String> response = start(user(USER), key, "{\"showtimeId\":\"x\",\"seatLabels\":[]}");

        assertEquals(400, response.getStatusCode().value());
        assertEquals("VALIDATION_ERROR", tree(response).get("error").asText());
        assertEquals(2, tree(response).get("details").size());
        assertNotNull(tree(response).get("traceId"));
        assertEquals(0, RECEIVED.size());
    }

    @Test
    void theSameKeyAndPayloadReplayTheSagaAndRunNoStepAgain() throws Exception {
        String id = tree(start(user(USER), key, BODY)).get("id").asText();
        RECEIVED.clear();

        ResponseEntity<String> replay = start(user(USER), key, BODY);

        assertEquals(200, replay.getStatusCode().value());
        assertEquals(id, tree(replay).get("id").asText());
        assertEquals(0, RECEIVED.size());
    }

    @Test
    void theSameKeyWithAnotherPayloadIsA409() {
        start(user(USER), key, BODY);

        ResponseEntity<String> conflict = start(user(USER), key,
                "{\"showtimeId\":\"22222222-2222-4222-8222-222222222222\",\"seatLabels\":[\"B7\"]}");

        assertEquals(409, conflict.getStatusCode().value());
    }

    @Test
    void aFailedConfirmationReleasesTheHoldAndTheAnswerNamesOnlyTheFailedStep() throws Exception {
        confirmStatus = 422;

        ResponseEntity<String> response = start(user(USER), key, BODY);

        assertEquals(201, response.getStatusCode().value());
        JsonNode saga = tree(response);
        assertEquals("COMPENSATED", saga.get("status").asText());
        assertEquals("confirm-reservation", saga.get("failedStep").asText());
        assertEquals(3, RECEIVED.size());
        assertTrue(RECEIVED.get(2).path().endsWith("/reservations/" + RESERVATION + "/release"));
        assertEquals(saga.get("id").asText() + ":release-seats", RECEIVED.get(2).idempotencyKey());
        assertFalse(response.getBody().contains("INVALID_STATUS_TRANSITION"));
        assertFalse(response.getBody().contains("422"));
    }

    @Test
    void aSagaCanBeReadByItsOwnerAndIsA404ForAnyoneElse() throws Exception {
        String id = tree(start(user(USER), key, BODY)).get("id").asText();

        ResponseEntity<String> own = http.exchange("/sagas/" + id, HttpMethod.GET,
                new HttpEntity<>(headers(user(USER), null)), String.class);
        ResponseEntity<String> other = http.exchange("/sagas/" + id, HttpMethod.GET,
                new HttpEntity<>(headers(user(OTHER_USER), null)), String.class);

        assertEquals(200, own.getStatusCode().value());
        assertEquals("COMPLETED", tree(own).get("status").asText());
        assertEquals(404, other.getStatusCode().value());
        assertEquals("NOT_FOUND", tree(other).get("error").asText());
    }

    private static String user(String subject) {
        return sign(Map.of("sub", subject, "exp", System.currentTimeMillis() / 1000 + 900, "iss", "csp-auth",
                "aud", List.of("csp-api")));
    }

    private static String service() {
        return sign(Map.of("sub", "csp-workflow", "exp", System.currentTimeMillis() / 1000 + 900, "iss", "csp-auth",
                "aud", "csp-internal"));
    }

    private static String sign(Map<String, Object> claims) {
        try {
            String header = encode(JSON.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
            String payload = encode(JSON.writeValueAsBytes(claims));
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(KEYS.getPrivate());
            rsa.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return header + "." + payload + "." + encode(rsa.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static KeyPair newKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startBooking() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                RECEIVED.add(new Received(path, exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                        exchange.getRequestHeaders().getFirst("X-Correlation-Id"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                int status = 200;
                String body = "{\"id\":\"" + RESERVATION + "\",\"status\":\"HELD\"}";
                if (path.endsWith("/holds")) {
                    status = 201;
                } else if (path.endsWith("/confirm") && confirmStatus != 200) {
                    status = confirmStatus;
                    body = "{\"error\":\"INVALID_STATUS_TRANSITION\",\"message\":\"expired\"}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
