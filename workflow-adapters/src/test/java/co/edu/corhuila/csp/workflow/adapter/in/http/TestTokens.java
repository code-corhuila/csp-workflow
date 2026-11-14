package co.edu.corhuila.csp.workflow.adapter.in.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Signs RS256 tokens with a throwaway key pair, as the identity service does with its own. */
final class TestTokens {

    static final String USER_ID = "11111111-1111-4111-8111-111111111111";

    private static final ObjectMapper JSON = new ObjectMapper();
    private final KeyPair keys;

    TestTokens() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keys = generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String publicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n";
    }

    String user(String subject, long expiresAtEpochSecond) {
        return sign("RS256", Map.of("sub", subject, "exp", expiresAtEpochSecond, "iss", "csp-auth",
                "aud", List.of("csp-api")));
    }

    String service(String subject, long expiresAtEpochSecond) {
        return sign("RS256", Map.of("sub", subject, "exp", expiresAtEpochSecond, "iss", "csp-auth",
                "aud", "csp-internal"));
    }

    String withClaims(Map<String, Object> claims) {
        return sign("RS256", claims);
    }

    String withAlgorithm(String algorithm, Map<String, Object> claims) {
        return sign(algorithm, claims);
    }

    String signedByAnotherKey(String subject, long expiresAtEpochSecond) {
        return new TestTokens().user(subject, expiresAtEpochSecond);
    }

    private String sign(String algorithm, Map<String, Object> claims) {
        try {
            String header = encode(JSON.writeValueAsBytes(Map.of("alg", algorithm, "typ", "JWT")));
            String payload = encode(JSON.writeValueAsBytes(claims));
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(keys.getPrivate());
            rsa.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return header + "." + payload + "." + encode(rsa.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
