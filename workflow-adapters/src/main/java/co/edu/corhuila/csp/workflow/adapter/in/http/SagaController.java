package co.edu.corhuila.csp.workflow.adapter.in.http;

import co.edu.corhuila.csp.workflow.adapter.Correlation;
import co.edu.corhuila.csp.workflow.application.port.in.GetSaga;
import co.edu.corhuila.csp.workflow.application.port.in.StartBookSeatsSaga;
import co.edu.corhuila.csp.workflow.application.port.in.StartResult;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Inbound HTTP adapter of the sagas: it translates, it decides nothing (Annex C). */
@RestController
public class SagaController {

    private final StartBookSeatsSaga startBookSeats;
    private final GetSaga getSaga;

    public SagaController(StartBookSeatsSaga startBookSeats, GetSaga getSaga) {
        this.startBookSeats = startBookSeats;
        this.getSaga = getSaga;
    }

    @PostMapping("/sagas/book-seats")
    public ResponseEntity<SagaResponse> bookSeats(
            @RequestAttribute(AuthFilter.USER_ID_ATTRIBUTE) UUID userId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        String correlationId = MDC.get(Correlation.MDC_KEY);
        StartResult result = startBookSeats.start(BookSeatsRequestParser.parse(userId,
                correlationId == null ? UUID.randomUUID().toString() : correlationId, idempotencyKey, body));
        SagaResponse response = SagaResponse.from(result.saga());
        if (!result.created()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.created(URI.create(request.getContextPath() + "/sagas/" + response.id())).body(response);
    }

    @GetMapping("/sagas/{sagaId}")
    public SagaResponse get(@RequestAttribute(AuthFilter.USER_ID_ATTRIBUTE) UUID userId, @PathVariable UUID sagaId) {
        return SagaResponse.from(getSaga.get(userId, sagaId));
    }
}
