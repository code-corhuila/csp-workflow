package co.edu.corhuila.csp.workflow.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import co.edu.corhuila.csp.workflow.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.csp.workflow.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.csp.workflow.domain.saga.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.workflow.domain.saga.UnknownSagaException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ErrorHandlerTest {

    private final ErrorHandler handler = new ErrorHandler();

    @BeforeEach
    void setUp() {
        MDC.put("correlationId", "trace-1");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void validationIsA400WithOneDetailPerField() {
        ResponseEntity<ApiError> answer = handler.validation(new ValidationException("invalid input",
                List.of(new FieldError("showtimeId", "must be a UUID"), new FieldError("seatLabels", "must not be empty"))));

        assertEquals(HttpStatus.BAD_REQUEST, answer.getStatusCode());
        assertEquals("VALIDATION_ERROR", answer.getBody().error());
        assertEquals(2, answer.getBody().details().size());
        assertEquals("trace-1", answer.getBody().traceId());
    }

    @Test
    void anUnknownSagaIsA404WithTheEnvelope() {
        ResponseEntity<ApiError> answer = handler.unknown(new UnknownSagaException(UUID.randomUUID()));

        assertEquals(HttpStatus.NOT_FOUND, answer.getStatusCode());
        assertEquals("NOT_FOUND", answer.getBody().error());
    }

    @Test
    void aReusedKeyWithAnotherPayloadIsA409() {
        ResponseEntity<ApiError> answer = handler.conflict(new IdempotencyKeyConflictException());

        assertEquals(HttpStatus.CONFLICT, answer.getStatusCode());
        assertEquals("CONFLICT", answer.getBody().error());
    }

    @Test
    void anUnexpectedErrorIsA500ThatLeaksNothing() {
        ResponseEntity<ApiError> answer = handler.unexpected(new IllegalStateException("jdbc:postgresql://secret-host/db"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, answer.getStatusCode());
        assertEquals("INTERNAL_ERROR", answer.getBody().error());
        assertFalse(answer.getBody().message().contains("secret-host"));
    }
}
