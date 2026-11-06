package co.edu.corhuila.csp.workflow.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationFilterTest {

    private final CorrelationFilter filter = new CorrelationFilter();

    private String run(String incoming, MockHttpServletResponse response, AtomicReference<String> seenInChain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/sagas/x");
        if (incoming != null) {
            request.addHeader("X-Correlation-Id", incoming);
        }
        FilterChain chain = (req, res) -> seenInChain.set(MDC.get("correlationId"));
        filter.doFilter(request, response, chain);
        return response.getHeader("X-Correlation-Id");
    }

    @Test
    void reusesTheCorrelationIdOfTheRequestEverywhere() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();

        String answered = run("5c9d7e1a-0000-4000-8000-000000000001", new MockHttpServletResponse(), seen);

        assertEquals("5c9d7e1a-0000-4000-8000-000000000001", answered);
        assertEquals("5c9d7e1a-0000-4000-8000-000000000001", seen.get());
    }

    @Test
    void createsOneWhenTheRequestHasNone() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();

        String answered = run(null, new MockHttpServletResponse(), seen);

        assertNotEquals("", answered);
        assertEquals(answered, seen.get());
    }

    @Test
    void replacesAnOversizedCorrelationId() throws Exception {
        String oversized = "x".repeat(129);

        String answered = run(oversized, new MockHttpServletResponse(), new AtomicReference<>());

        assertNotEquals(oversized, answered);
    }

    @Test
    void clearsTheMdcWhenTheRequestEnds() throws Exception {
        run("5c9d7e1a-0000-4000-8000-000000000001", new MockHttpServletResponse(), new AtomicReference<>());

        assertNull(MDC.get("correlationId"));
    }
}
