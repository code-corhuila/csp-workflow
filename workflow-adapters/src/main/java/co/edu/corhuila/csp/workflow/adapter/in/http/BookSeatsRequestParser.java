package co.edu.corhuila.csp.workflow.adapter.in.http;

import co.edu.corhuila.csp.workflow.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.csp.workflow.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.csp.workflow.application.port.in.BookSeatsCommand;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Validates the request of {@code POST /sagas/book-seats} field by field, as {@code workflow-service.yaml} declares
 * it, and reports every broken field at once. A field the contract does not declare is rejected, which includes a
 * {@code userId}: the user is the one of the token and never comes from the body.
 */
final class BookSeatsRequestParser {

    private static final int KEY_MIN = 8;
    private static final int KEY_MAX = 128;
    private static final int SEATS_MAX = 10;
    private static final Set<String> FIELDS = Set.of("showtimeId", "seatLabels");

    private BookSeatsRequestParser() { }

    static BookSeatsCommand parse(UUID userId, String correlationId, String idempotencyKey, JsonNode body) {
        List<FieldError> errors = new ArrayList<>();
        if (idempotencyKey == null || idempotencyKey.length() < KEY_MIN || idempotencyKey.length() > KEY_MAX) {
            errors.add(new FieldError("Idempotency-Key", "is required and must have 8 to 128 characters"));
        }
        if (body == null || !body.isObject()) {
            errors.add(new FieldError("body", "must be a JSON object"));
            throw new ValidationException("invalid input", errors);
        }
        UUID showtimeId = showtimeId(body.get("showtimeId"), errors);
        List<String> seatLabels = seatLabels(body.get("seatLabels"), errors);
        body.fieldNames().forEachRemaining(name -> {
            if (!FIELDS.contains(name)) {
                errors.add(new FieldError(name, "is not a field of this request"));
            }
        });
        if (!errors.isEmpty()) {
            throw new ValidationException("invalid input", errors);
        }
        return new BookSeatsCommand(userId, idempotencyKey, correlationId, showtimeId, seatLabels);
    }

    private static UUID showtimeId(JsonNode node, List<FieldError> errors) {
        if (node == null || !node.isTextual()) {
            errors.add(new FieldError("showtimeId", "is required and must be a UUID"));
            return null;
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            errors.add(new FieldError("showtimeId", "must be a UUID"));
            return null;
        }
    }

    private static List<String> seatLabels(JsonNode node, List<FieldError> errors) {
        String problem = "is required and must be 1 to 10 unique, non-empty strings";
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > SEATS_MAX) {
            errors.add(new FieldError("seatLabels", problem));
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isEmpty() || !seen.add(item.asText())) {
                errors.add(new FieldError("seatLabels", problem));
                return List.of();
            }
            labels.add(item.asText());
        }
        return labels;
    }
}
