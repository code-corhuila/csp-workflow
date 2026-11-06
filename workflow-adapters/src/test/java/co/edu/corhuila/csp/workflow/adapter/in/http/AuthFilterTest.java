package co.edu.corhuila.csp.workflow.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthFilterTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final long LATER = NOW.getEpochSecond() + 900;

    private final TestTokens tokens = new TestTokens();
    private final AuthFilter filter = new AuthFilter(new Rs256Verifier(tokens.publicKeyPem()), new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private MockHttpServletResponse call(MockHttpServletRequest request, MockFilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest request(String path, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1" + path);
        request.setContextPath("/api/v1");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    @Test
    void aMissingTokenIsUnauthorized() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = call(request("/sagas/x", null), chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"error\":\"UNAUTHORIZED\""));
        assertNull(chain.getRequest());
    }

    @Test
    void anInvalidTokenIsUnauthorizedWithTheInvalidTokenCode() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = call(request("/sagas/x", "Bearer " + tokens.signedByAnotherKey(TestTokens.USER_ID, LATER)), chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"error\":\"INVALID_TOKEN\""));
        assertNull(chain.getRequest());
    }

    @Test
    void anExpiredTokenIsUnauthorized() throws Exception {
        MockHttpServletResponse response = call(
                request("/sagas/x", "Bearer " + tokens.user(TestTokens.USER_ID, NOW.getEpochSecond() - 120)), new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    void aSubjectThatIsNotAUserIdIsUnauthorized() throws Exception {
        MockHttpServletResponse response = call(
                request("/sagas/x", "Bearer " + tokens.user("csp-worker", LATER)), new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    void aServiceCredentialIsForbiddenOnAUserRoute() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = call(request("/sagas/x", "Bearer " + tokens.service(TestTokens.USER_ID, LATER)), chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"error\":\"FORBIDDEN\""));
        assertNull(chain.getRequest());
    }

    @Test
    void aUserTokenPassesAndTheUserIdIsReadFromItsSubject() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = request("/sagas/x", "Bearer " + tokens.user(TestTokens.USER_ID, LATER));

        MockHttpServletResponse response = call(request, chain);

        assertEquals(200, response.getStatus());
        assertEquals(UUID.fromString(TestTokens.USER_ID), request.getAttribute(AuthFilter.USER_ID_ATTRIBUTE));
    }

    @Test
    void theHealthRouteNeedsNoToken() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        call(request("/health", null), chain);

        assertEquals("/api/v1/health", ((MockHttpServletRequest) chain.getRequest()).getRequestURI());
    }
}
