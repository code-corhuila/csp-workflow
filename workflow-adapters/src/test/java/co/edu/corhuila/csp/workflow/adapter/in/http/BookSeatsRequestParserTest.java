package co.edu.corhuila.csp.workflow.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.csp.workflow.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.csp.workflow.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.csp.workflow.application.port.in.BookSeatsCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookSeatsRequestParserTest {

    private static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String SHOWTIME = "22222222-2222-4222-8222-222222222222";
    private static final String KEY = "key-0000000000001";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode body(String json) throws Exception {
        return JSON.readTree(json);
    }

    private static List<String> fieldsOf(ValidationException e) {
        return e.details().stream().map(FieldError::field).toList();
    }

    @Test
    void aValidRequestBecomesTheCommandOfTheTokenUser() throws Exception {
        BookSeatsCommand command = BookSeatsRequestParser.parse(USER, KEY,
                body("{\"showtimeId\":\"" + SHOWTIME + "\",\"seatLabels\":[\"A1\",\"A2\"]}"));

        assertEquals(USER, command.userId());
        assertEquals(KEY, command.idempotencyKey());
        assertEquals(UUID.fromString(SHOWTIME), command.showtimeId());
        assertEquals(List.of("A1", "A2"), command.seatLabels());
    }

    @Test
    void everyBrokenFieldGetsItsOwnDetail() throws Exception {
        ValidationException e = assertThrows(ValidationException.class,
                () -> BookSeatsRequestParser.parse(USER, "short", body("{\"showtimeId\":\"nope\",\"seatLabels\":[]}")));

        assertEquals(List.of("Idempotency-Key", "showtimeId", "seatLabels"), fieldsOf(e));
    }

    @Test
    void theIdempotencyKeyIsRequiredAndBounded() throws Exception {
        String valid = "{\"showtimeId\":\"" + SHOWTIME + "\",\"seatLabels\":[\"A1\"]}";

        assertThrows(ValidationException.class, () -> BookSeatsRequestParser.parse(USER, null, body(valid)));
        assertThrows(ValidationException.class, () -> BookSeatsRequestParser.parse(USER, "1234567", body(valid)));
        assertThrows(ValidationException.class, () -> BookSeatsRequestParser.parse(USER, "k".repeat(129), body(valid)));
        BookSeatsRequestParser.parse(USER, "k".repeat(8), body(valid));
        BookSeatsRequestParser.parse(USER, "k".repeat(128), body(valid));
    }

    @Test
    void theShowtimeIsRequiredAndMustBeAUuid() throws Exception {
        assertEquals(List.of("showtimeId"), fieldsOf(assertThrows(ValidationException.class,
                () -> BookSeatsRequestParser.parse(USER, KEY, body("{\"seatLabels\":[\"A1\"]}")))));
        assertEquals(List.of("showtimeId"), fieldsOf(assertThrows(ValidationException.class,
                () -> BookSeatsRequestParser.parse(USER, KEY, body("{\"showtimeId\":7,\"seatLabels\":[\"A1\"]}")))));
    }

    @Test
    void theSeatsAreOneToTenUniqueNonBlankStrings() throws Exception {
        String prefix = "{\"showtimeId\":\"" + SHOWTIME + "\",\"seatLabels\":";
        List<String> invalid = List.of(prefix + "[]}", prefix + "\"A1\"}", prefix + "[\"A1\",\"A1\"]}",
                prefix + "[\"\"]}", prefix + "[1]}",
                prefix + "[\"A1\",\"A2\",\"A3\",\"A4\",\"A5\",\"A6\",\"A7\",\"A8\",\"A9\",\"A10\",\"A11\"]}");

        for (String json : invalid) {
            assertEquals(List.of("seatLabels"), fieldsOf(assertThrows(ValidationException.class,
                    () -> BookSeatsRequestParser.parse(USER, KEY, body(json)))), json);
        }
    }

    @Test
    void aFieldTheContractDoesNotDeclareIsRejectedEvenTheUserId() throws Exception {
        ValidationException e = assertThrows(ValidationException.class, () -> BookSeatsRequestParser.parse(USER, KEY,
                body("{\"showtimeId\":\"" + SHOWTIME + "\",\"seatLabels\":[\"A1\"],\"userId\":\"" + USER + "\"}")));

        assertEquals(List.of("userId"), fieldsOf(e));
    }

    @Test
    void aBodyThatIsNotAnObjectIsRejected() throws Exception {
        assertThrows(ValidationException.class, () -> BookSeatsRequestParser.parse(USER, KEY, body("[1]")));
        assertThrows(ValidationException.class, () -> BookSeatsRequestParser.parse(USER, KEY, null));
    }
}
