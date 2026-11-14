package co.edu.corhuila.csp.workflow.adapter.in.http;

import co.edu.corhuila.csp.workflow.adapter.Correlation;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.slf4j.MDC;

/**
 * The shared error envelope (_shared.yaml ErrorResponse): {@code error} carries the code, {@code message} a
 * neutral text and {@code traceId} the correlation id to find the full error in the logs. Clients branch on the
 * code, never on the message.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String error, String message, List<FieldError> details, String traceId) {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String INVALID_TOKEN = "INVALID_TOKEN";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String CONFLICT = "CONFLICT";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    public record FieldError(String field, String message) { }

    public static ApiError of(String code, String message, List<FieldError> details) {
        return new ApiError(code, message, details, MDC.get(Correlation.MDC_KEY));
    }

    public static ApiError of(String code, String message) {
        return of(code, message, List.of());
    }

    /** Thrown by the controller when the shape of the input is wrong: always 400. */
    public static class ValidationException extends RuntimeException {
        private final transient List<FieldError> details;

        public ValidationException(String message, List<FieldError> details) {
            super(message);
            this.details = List.copyOf(details);
        }

        public List<FieldError> details() {
            return details;
        }
    }
}
