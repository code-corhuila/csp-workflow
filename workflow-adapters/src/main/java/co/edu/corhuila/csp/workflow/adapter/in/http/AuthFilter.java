package co.edu.corhuila.csp.workflow.adapter.in.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the bearer token of a user on every route but {@code /health}. The user is the {@code sub} of the
 * token, which must be a UUID; a service credential (audience {@code csp-internal}) is not a user and gets 403
 * (ADR-066). A missing, expired or invalid token is a 401 before anything else happens.
 */
public class AuthFilter extends OncePerRequestFilter {

    public static final String USER_ID_ATTRIBUTE = "auth.userId";
    private static final String SERVICE_AUDIENCE = "csp-internal";

    private final Rs256Verifier verifier;
    private final ObjectMapper json;
    private final Clock clock;

    public AuthFilter(Rs256Verifier verifier, ObjectMapper json, Clock clock) {
        this.verifier = verifier;
        this.json = json;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length()).equals("/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() == 7) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, ApiError.UNAUTHORIZED, "a bearer token is required");
            return;
        }
        VerifiedToken token;
        try {
            token = verifier.verify(header.substring(7), clock.instant());
        } catch (Rs256Verifier.InvalidTokenException e) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, ApiError.INVALID_TOKEN, "the token is invalid or has expired");
            return;
        }
        if (token.audiences().contains(SERVICE_AUDIENCE)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, ApiError.FORBIDDEN, "a service credential cannot call this route");
            return;
        }
        try {
            request.setAttribute(USER_ID_ATTRIBUTE, UUID.fromString(token.subject()));
        } catch (IllegalArgumentException e) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, ApiError.INVALID_TOKEN, "the token is invalid or has expired");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ApiError.of(code, message));
    }
}
