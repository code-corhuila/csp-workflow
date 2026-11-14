package co.edu.corhuila.csp.workflow.adapter.out.participants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The credential of the workflow, issued by the identity service for the subject {@code csp-workflow} (ADR-052,
 * ADR-056). It has a lifetime, so with {@code SERVICE_TOKEN_FILE} the file is read again at every call and the
 * platform can rotate the secret without a restart; with {@code SERVICE_TOKEN} the value is fixed. One of the two is
 * required, and the service refuses to start without a token (norm 5.8.2).
 */
public final class ServiceToken {

    private final String fixedToken;
    private final Path tokenFile;

    public ServiceToken(String token, String file) {
        this.fixedToken = token == null ? "" : token.strip();
        this.tokenFile = file == null || file.isBlank() ? null : Path.of(file.strip());
        current();
    }

    public String current() {
        String token = tokenFile == null ? fixedToken : readFile();
        if (token.isBlank()) {
            throw new IllegalStateException(
                    "SERVICE_TOKEN or SERVICE_TOKEN_FILE is required: the participants reject an unauthenticated request");
        }
        return token;
    }

    private String readFile() {
        try {
            return Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            throw new IllegalStateException("the service token file cannot be read: " + tokenFile, exception);
        }
    }
}
