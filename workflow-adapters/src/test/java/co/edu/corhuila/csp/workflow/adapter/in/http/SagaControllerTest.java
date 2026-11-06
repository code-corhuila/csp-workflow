package co.edu.corhuila.csp.workflow.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.csp.workflow.application.port.in.BookSeatsCommand;
import co.edu.corhuila.csp.workflow.application.port.in.GetSaga;
import co.edu.corhuila.csp.workflow.application.port.in.StartBookSeatsSaga;
import co.edu.corhuila.csp.workflow.application.port.in.StartResult;
import co.edu.corhuila.csp.workflow.domain.saga.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.workflow.domain.saga.SagaInput;
import co.edu.corhuila.csp.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.csp.workflow.domain.saga.UnknownSagaException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The contract of `workflow-service.yaml` 1.0.0 (ADR-066): codes, headers and the fields of the answer. */
class SagaControllerTest {

    private static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SAGA_ID = UUID.fromString("35920dc9-bd55-41a0-8e41-5832377383e5");
    private static final UUID RESERVATION = UUID.fromString("9cfcfeec-c4b6-4d76-a0b4-f0db602d7caa");
    private static final String BODY = "{\"showtimeId\":\"22222222-2222-4222-8222-222222222222\",\"seatLabels\":[\"A1\",\"A2\"]}";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SagaInput INPUT = new SagaInput(UUID.fromString("22222222-2222-4222-8222-222222222222"), List.of("A1", "A2"));

    private final List<BookSeatsCommand> started = new ArrayList<>();
    private StartResult nextResult;
    private RuntimeException nextFailure;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        StartBookSeatsSaga start = command -> {
            started.add(command);
            if (nextFailure != null) {
                throw nextFailure;
            }
            return nextResult;
        };
        GetSaga get = (userId, sagaId) -> {
            if (!sagaId.equals(SAGA_ID) || !userId.equals(USER)) {
                throw new UnknownSagaException(sagaId);
            }
            return completed();
        };
        mvc = MockMvcBuilders.standaloneSetup(new SagaController(start, get))
                .setControllerAdvice(new ErrorHandler()).build();
        nextResult = new StartResult(completed(), true);
    }

    private static SagaInstance completed() {
        SagaInstance saga = SagaInstance.start(SAGA_ID, "book-seats", USER, "key-0000000000001", "fp",
                Instant.parse("2026-11-05T10:00:00Z"));
        saga.beginStep("hold-seats", Instant.parse("2026-11-05T10:00:01Z"));
        saga.attachReservation(RESERVATION);
        saga.completeStep("hold-seats", Instant.parse("2026-11-05T10:00:01Z"));
        saga.beginStep("confirm-reservation", Instant.parse("2026-11-05T10:00:02Z"));
        saga.completeStep("confirm-reservation", Instant.parse("2026-11-05T10:00:02Z"));
        saga.complete(Instant.parse("2026-11-05T10:00:02Z"));
        return saga;
    }

    private static MockHttpServletRequest authenticated(MockHttpServletRequest request) {
        request.setAttribute(AuthFilter.USER_ID_ATTRIBUTE, USER);
        return request;
    }

    @Test
    void startAnswers201WithLocationAndTheFieldsOfTheContract() throws Exception {
        String answer = mvc.perform(post("/sagas/book-seats").with(SagaControllerTest::authenticated)
                        .header("Idempotency-Key", "key-0000000000001")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/sagas/" + SAGA_ID))
                .andExpect(jsonPath("$.id").value(SAGA_ID.toString()))
                .andExpect(jsonPath("$.sagaName").value("book-seats"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedSteps[0]").value("hold-seats"))
                .andExpect(jsonPath("$.completedSteps[1]").value("confirm-reservation"))
                .andExpect(jsonPath("$.failedStep").doesNotExist())
                .andExpect(jsonPath("$.reservationId").value(RESERVATION.toString()))
                .andReturn().getResponse().getContentAsString();

        JsonNode tree = JSON.readTree(answer);
        List<String> names = new ArrayList<>();
        tree.fieldNames().forEachRemaining(names::add);
        assertEquals(List.of("completedSteps", "createdAt", "failedStep", "id", "reservationId", "sagaName", "status",
                "updatedAt"), names.stream().sorted().toList());
        assertEquals(true, tree.get("failedStep").isNull());
    }

    @Test
    void theCommandCarriesTheUserOfTheTokenAndTheKeyOfTheHeader() throws Exception {
        mvc.perform(post("/sagas/book-seats").with(SagaControllerTest::authenticated)
                .header("Idempotency-Key", "key-0000000000001")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isCreated());

        assertEquals(USER, started.get(0).userId());
        assertEquals("key-0000000000001", started.get(0).idempotencyKey());
        assertEquals(List.of("A1", "A2"), started.get(0).seatLabels());
    }

    @Test
    void aReplayAnswers200() throws Exception {
        nextResult = new StartResult(completed(), false);

        mvc.perform(post("/sagas/book-seats").with(SagaControllerTest::authenticated)
                        .header("Idempotency-Key", "key-0000000000001")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    void aMissingIdempotencyKeyIsA400NamingTheHeader() throws Exception {
        mvc.perform(post("/sagas/book-seats").with(SagaControllerTest::authenticated)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("Idempotency-Key"));
        assertEquals(0, started.size());
    }

    @Test
    void aBadBodyIsA400WithOneDetailPerField() throws Exception {
        mvc.perform(post("/sagas/book-seats").with(SagaControllerTest::authenticated)
                        .header("Idempotency-Key", "key-0000000000001")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"showtimeId\":\"x\",\"seatLabels\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.length()").value(2));
    }

    @Test
    void aBodyThatIsNotJsonIsA400() throws Exception {
        mvc.perform(post("/sagas/book-seats").with(SagaControllerTest::authenticated)
                        .header("Idempotency-Key", "key-0000000000001")
                        .contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void aReusedKeyWithAnotherPayloadIsA409() throws Exception {
        nextFailure = new IdempotencyKeyConflictException();

        mvc.perform(post("/sagas/book-seats").with(SagaControllerTest::authenticated)
                        .header("Idempotency-Key", "key-0000000000001")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void aFailedSagaNeverShowsItsInternalDetail() throws Exception {
        SagaInstance failed = SagaInstance.start(SAGA_ID, "book-seats", USER, "key-0000000000001", "fp",
                Instant.parse("2026-11-05T10:00:00Z"));
        failed.beginStep("hold-seats", Instant.parse("2026-11-05T10:00:01Z"));
        failed.stepFailed("hold-seats", "booking hold answered 503 at secret-host.internal",
                Instant.parse("2026-11-05T10:00:02Z"));
        nextResult = new StartResult(failed, true);

        mvc.perform(post("/sagas/book-seats").with(SagaControllerTest::authenticated)
                        .header("Idempotency-Key", "key-0000000000001")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPENSATED"))
                .andExpect(jsonPath("$.failedStep").value("hold-seats"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-host"))));
    }

    @Test
    void getAnswers200ForTheOwner() throws Exception {
        mvc.perform(get("/sagas/" + SAGA_ID).with(SagaControllerTest::authenticated))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SAGA_ID.toString()));
    }

    @Test
    void getAnswers404WithTheEnvelopeForAnUnknownSagaOrAnotherUser() throws Exception {
        mvc.perform(get("/sagas/" + UUID.randomUUID()).with(SagaControllerTest::authenticated))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mvc.perform(get("/sagas/not-a-uuid").with(SagaControllerTest::authenticated))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mvc.perform(get("/sagas/" + SAGA_ID).with(request -> {
                    request.setAttribute(AuthFilter.USER_ID_ATTRIBUTE, UUID.randomUUID());
                    return request;
                }))
                .andExpect(status().isNotFound());
    }
}
