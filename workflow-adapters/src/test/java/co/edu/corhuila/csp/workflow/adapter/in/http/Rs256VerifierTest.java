package co.edu.corhuila.csp.workflow.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.csp.workflow.adapter.in.http.Rs256Verifier.InvalidTokenException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Rs256VerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final long LATER = NOW.getEpochSecond() + 900;

    private final TestTokens tokens = new TestTokens();
    private final Rs256Verifier verifier = new Rs256Verifier(tokens.publicKeyPem());

    @Test
    void aValidTokenGivesItsSubjectAndAudiences() throws Exception {
        VerifiedToken token = verifier.verify(tokens.user(TestTokens.USER_ID, LATER), NOW);

        assertEquals(TestTokens.USER_ID, token.subject());
        assertEquals(List.of("csp-api"), token.audiences());
    }

    @Test
    void anAudienceGivenAsAStringIsAccepted() throws Exception {
        VerifiedToken token = verifier.verify(tokens.service("csp-workflow", LATER), NOW);

        assertEquals(List.of("csp-internal"), token.audiences());
    }

    @Test
    void aTokenWithoutAudienceHasNoAudiences() throws Exception {
        VerifiedToken token = verifier.verify(tokens.withClaims(Map.of("sub", TestTokens.USER_ID, "exp", LATER)), NOW);

        assertEquals(List.of(), token.audiences());
    }

    @Test
    void aTokenSignedByAnotherKeyIsRejected() {
        String forged = tokens.signedByAnotherKey(TestTokens.USER_ID, LATER);

        assertThrows(InvalidTokenException.class, () -> verifier.verify(forged, NOW));
    }

    @Test
    void anAlgorithmOtherThanRs256IsRejected() {
        String none = tokens.withAlgorithm("none", Map.of("sub", TestTokens.USER_ID, "exp", LATER));
        String hs256 = tokens.withAlgorithm("HS256", Map.of("sub", TestTokens.USER_ID, "exp", LATER));

        assertThrows(InvalidTokenException.class, () -> verifier.verify(none, NOW));
        assertThrows(InvalidTokenException.class, () -> verifier.verify(hs256, NOW));
    }

    @Test
    void anExpiredTokenIsRejectedBeyondTheLeeway() throws Exception {
        verifier.verify(tokens.user(TestTokens.USER_ID, NOW.getEpochSecond() - 10), NOW);

        assertThrows(InvalidTokenException.class,
                () -> verifier.verify(tokens.user(TestTokens.USER_ID, NOW.getEpochSecond() - 60), NOW));
    }

    @Test
    void aTokenWithoutExpirationOrSubjectIsRejected() {
        String noExp = tokens.withClaims(Map.of("sub", TestTokens.USER_ID));
        String noSub = tokens.withClaims(Map.of("exp", LATER));

        assertThrows(InvalidTokenException.class, () -> verifier.verify(noExp, NOW));
        assertThrows(InvalidTokenException.class, () -> verifier.verify(noSub, NOW));
    }

    @Test
    void aMalformedTokenIsRejected() {
        assertThrows(InvalidTokenException.class, () -> verifier.verify("not-a-token", NOW));
        assertThrows(InvalidTokenException.class, () -> verifier.verify("a.b.c", NOW));
    }

    @Test
    void aKeyThatIsNotAPublicKeyIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Rs256Verifier("secret"));
    }
}
