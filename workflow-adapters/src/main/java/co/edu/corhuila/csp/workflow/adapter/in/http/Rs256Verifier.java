package co.edu.corhuila.csp.workflow.adapter.in.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Each service validates the token itself (norm 5.3.7). The gateway only filters requests that carry no
 * credentials; a service that trusted a header set upstream would accept anyone who reaches it.
 */
public class Rs256Verifier {

    public static class InvalidTokenException extends Exception {
        InvalidTokenException(String message) {
            super(message);
        }
    }

    private static final long LEEWAY_SECONDS = 30;

    private final PublicKey key;
    private final ObjectMapper json = new ObjectMapper();

    public Rs256Verifier(String publicKeyPem) {
        if (publicKeyPem == null || !publicKeyPem.contains("BEGIN PUBLIC KEY")) {
            throw new IllegalArgumentException("JWT public key is not a PEM public key");
        }
        String base64 = publicKeyPem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        try {
            key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("JWT public key cannot be read", e);
        }
    }

    public VerifiedToken verify(String token, Instant now) throws InvalidTokenException {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new InvalidTokenException("malformed token");
        }
        try {
            requireRs256(json.readTree(Base64.getUrlDecoder().decode(parts[0])));
            requireSignature(parts);
            JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (!claims.path("exp").isNumber() || !claims.path("sub").isTextual()) {
                throw new InvalidTokenException("exp and sub are required");
            }
            if (now.getEpochSecond() > claims.get("exp").asLong() + LEEWAY_SECONDS) {
                throw new InvalidTokenException("expired");
            }
            return new VerifiedToken(claims.get("sub").asText(), audiencesOf(claims.path("aud")));
        } catch (InvalidTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidTokenException("unreadable token");
        }
    }

    /** A closed rule: a token that declares "none" or HS256 is rejected, not checked with the wrong algorithm. */
    private static void requireRs256(JsonNode header) throws InvalidTokenException {
        if (!"RS256".equals(header.path("alg").asText())) {
            throw new InvalidTokenException("algorithm not allowed");
        }
    }

    private void requireSignature(String[] parts) throws GeneralSecurityException, InvalidTokenException {
        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initVerify(key);
        rsa.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!rsa.verify(Base64.getUrlDecoder().decode(parts[2]))) {
            throw new InvalidTokenException("bad signature");
        }
    }

    /** {@code aud} is a string or a list of strings. */
    private static List<String> audiencesOf(JsonNode aud) {
        List<String> audiences = new ArrayList<>();
        if (aud.isTextual()) {
            audiences.add(aud.asText());
        } else if (aud.isArray()) {
            aud.forEach(item -> audiences.add(item.asText()));
        }
        return audiences;
    }
}
